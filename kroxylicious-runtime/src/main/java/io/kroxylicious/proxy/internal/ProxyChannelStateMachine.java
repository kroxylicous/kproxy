/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.slf4j.Logger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.haproxy.HAProxyMessage;

import io.kroxylicious.proxy.filter.FilterAndInvoker;
import io.kroxylicious.proxy.filter.NetFilter;
import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.RequestFrame;
import io.kroxylicious.proxy.internal.ProxyChannelState.Closed;
import io.kroxylicious.proxy.internal.ProxyChannelState.Forwarding;
import io.kroxylicious.proxy.internal.codec.FrameOversizedException;
import io.kroxylicious.proxy.model.VirtualCluster;
import io.kroxylicious.proxy.service.HostPort;
import io.kroxylicious.proxy.tag.VisibleForTesting;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import static io.kroxylicious.proxy.internal.ProxyChannelState.Startup.STARTING_STATE;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * <p>The state machine for a single client's connection to a server.
 * The "session state" is held in the {@link #state} field and is represented by an immutable
 * subclass of {@link ProxyChannelState} which contains state-specific data.
 * Events which cause state transitions are represented by the {@code on*()} family of methods.
 * Depending on the transition the frontend or backend handlers may get notified via one if their
 * {@code in*()} methods.
 * </p>
 *
 * <pre>
 *   «start»
 *      │
 *      ↓ frontend.{@link KafkaProxyFrontendHandler#channelActive(ChannelHandlerContext) channelActive}
 *     {@link ProxyChannelState.ClientActive ClientActive} ╌╌╌╌⤍ <b>error</b> ╌╌╌╌⤍
 *  ╭───┤
 *  ↓   ↓ frontend.{@link KafkaProxyFrontendHandler#channelRead(ChannelHandlerContext, Object) channelRead} receives a PROXY header
 *  │  {@link ProxyChannelState.HaProxy HaProxy} ╌╌╌╌⤍ <b>error</b> ╌╌╌╌⤍
 *  ╰───┤
 *  ╭───┤
 *  ↓   ↓ frontend.{@link KafkaProxyFrontendHandler#channelRead(ChannelHandlerContext, Object) channelRead} receives an ApiVersions request
 *  │  {@link ProxyChannelState.ApiVersions ApiVersions} ╌╌╌╌⤍ <b>error</b> ╌╌╌╌⤍
 *  ╰───┤
 *      ↓ frontend.{@link KafkaProxyFrontendHandler#channelRead(ChannelHandlerContext, Object) channelRead} receives any other KRPC request
 *     {@link ProxyChannelState.SelectingServer SelectingServer} ╌╌╌╌⤍ <b>error</b> ╌╌╌╌⤍
 *      │
 *      ↓ netFiler.{@link NetFilter#selectServer(NetFilter.NetFilterContext) selectServer} calls frontend.{@link KafkaProxyFrontendHandler#initiateConnect(HostPort, List) initiateConnect}
 *     {@link ProxyChannelState.Connecting Connecting} ╌╌╌╌⤍ <b>error</b> ╌╌╌╌⤍
 *      │
 *      ↓
 *     {@link Forwarding Forwarding} ╌╌╌╌⤍ <b>error</b> ╌╌╌╌⤍
 *      │ backend.{@link KafkaProxyBackendHandler#channelInactive(ChannelHandlerContext) channelInactive}
 *      │ or frontend.{@link KafkaProxyFrontendHandler#channelInactive(ChannelHandlerContext) channelInactive}
 *      ↓
 *     {@link Closed Closed} ⇠╌╌╌╌ <b>error</b> ⇠╌╌╌╌
 * </pre>
 *
 * <p>In addition to the "session state" this class also manages a second state machine for
 * handling TCP backpressure via the {@link #clientReadsBlocked} and {@link #serverReadsBlocked} field:</p>
 *
 * <pre>
 *     bothBlocked ←────────────────→ serverBlocked
 *         ↑                                ↑
 *         │                                │
 *         ↓                                ↓
 *    clientBlocked ←───────────────→ neitherBlocked
 * </pre>
 * <p>Note that this backpressure state machine is not related to the
 * session state machine: in general backpressure could happen in
 * several of the session states.</p>
 */
public class ProxyChannelStateMachine {
    private static final Logger LOGGER = getLogger(ProxyChannelStateMachine.class);

    /**
     * The current state. This can be changed via a call to one of the {@code on*()} methods.
     */
    @NonNull
    private ProxyChannelState state = STARTING_STATE;

    /*
     * The netty autoread flag is volatile =>
     * expensive to set in every call to channelRead.
     * So we track autoread states via these non-volatile fields,
     * allowing us to only touch the volatile when it needs to be changed
     */
    @VisibleForTesting
    boolean serverReadsBlocked;
    @VisibleForTesting
    boolean clientReadsBlocked;

    /**
     * The frontend handler. Non-null if we got as far as ClientActive.
     */
    @SuppressWarnings({ "DataFlowIssue", "java:S2637" })
    @NonNull
    private KafkaProxyFrontendHandler frontendHandler = null;

    /**
     * The backend handler. Non-null if {@link #onNetFilterInitiateConnect(HostPort, List, VirtualCluster, NetFilter)}
     * has been called
     */
    @VisibleForTesting
    @Nullable
    private KafkaProxyBackendHandler backendHandler;

    ProxyChannelState state() {
        return state;
    }

    /**
     * Purely for tests DO NOT USE IN PRODUCTION code!!
     * Sonar will complain if one uses this in prod code listen to it.
     */
    @VisibleForTesting
    void forceState(@NonNull ProxyChannelState state, @NonNull KafkaProxyFrontendHandler frontendHandler, @Nullable KafkaProxyBackendHandler backendHandler) {
        LOGGER.info("Forcing state to {} with {} and {}", state, frontendHandler, backendHandler);
        this.state = state;
        this.frontendHandler = frontendHandler;
        this.backendHandler = backendHandler;
    }

    @Override
    public String toString() {
        return "StateHolder{" +
                "state=" + state +
                ", serverReadsBlocked=" + serverReadsBlocked +
                ", clientReadsBlocked=" + clientReadsBlocked +
                ", frontendHandler=" + frontendHandler +
                ", backendHandler=" + backendHandler +
                '}';
    }

    public String currentState() {
        return this.state().getClass().getSimpleName();
    }

    void onClientUnwritable() {
        if (!serverReadsBlocked) {
            serverReadsBlocked = true;
            Objects.requireNonNull(backendHandler).blockServerReads();
        }
    }

    void onClientWritable() {
        if (serverReadsBlocked) {
            serverReadsBlocked = false;
            Objects.requireNonNull(backendHandler).unblockServerReads();
        }
    }

    /**
     * The channel to the server is no longer writable
     */
    void onServerUnwritable() {
        if (!clientReadsBlocked) {
            clientReadsBlocked = true;
            frontendHandler.blockClientReads();
        }
    }

    /**
     * The channel to the server is now writable
     */
    void onServerWritable() {
        if (clientReadsBlocked) {
            clientReadsBlocked = false;
            frontendHandler.unblockClientReads();
        }
    }

    void onClientActive(@NonNull KafkaProxyFrontendHandler frontendHandler) {
        if (STARTING_STATE.equals(this.state)) {
            this.frontendHandler = frontendHandler;
            toClientActive(STARTING_STATE.toCLientActive(), frontendHandler);
        }
        else {
            illegalState("Client activation while not in the start state");
        }
    }

    private void toClientActive(
                                @NonNull ProxyChannelState.ClientActive clientActive,
                                @NonNull KafkaProxyFrontendHandler frontendHandler) {
        setState(clientActive);
        frontendHandler.inClientActive();
    }

    void onNetFilterInitiateConnect(
                                    @NonNull HostPort remote,
                                    @NonNull List<FilterAndInvoker> filters,
                                    VirtualCluster virtualCluster,
                                    NetFilter netFilter) {
        if (state instanceof ProxyChannelState.SelectingServer selectingServerState) {
            toConnecting(selectingServerState.toConnecting(remote), filters, virtualCluster);
        }
        else {
            String msg = "NetFilter called NetFilterContext.initiateConnect() more than once";
            illegalState(msg + " : filter='" + netFilter + "'");
        }
    }

    private void toConnecting(ProxyChannelState.Connecting connecting,
                              @NonNull List<FilterAndInvoker> filters,
                              VirtualCluster virtualCluster) {
        setState(connecting);
        backendHandler = new KafkaProxyBackendHandler(this, virtualCluster);
        frontendHandler.inConnecting(connecting.remote(), filters, backendHandler);
    }

    void onServerActive() {
        if (state() instanceof ProxyChannelState.Connecting connectedState) {
            toForwarding(connectedState.toForwarding());
        }
        else {
            illegalState("Server became active while not in the connecting state");
        }
    }

    private void toForwarding(Forwarding forwarding) {
        setState(forwarding);
        Objects.requireNonNull(frontendHandler).inForwarding();
    }

    void illegalState(@NonNull String msg) {
        if (!(state instanceof Closed)) {
            LOGGER.error("Unexpected event while in {} message: {}, closing channels with no client response.", state, msg);
            toClosed(null);
        }
    }

    void forwardToClient(Object msg) {
        Objects.requireNonNull(frontendHandler).forwardToClient(msg);
    }

    void forwardToServer(Object msg) {
        Objects.requireNonNull(backendHandler).forwardToServer(msg);
    }

    void onClientRequest(@NonNull SaslDecodePredicate dp,
                         Object msg) {
        Objects.requireNonNull(frontendHandler);
        if (state() instanceof Forwarding) { // post-backend connection
            forwardToServer(msg);
        }
        else {
            if (!onClientRequestBeforeForwarding(dp, msg)) {
                illegalState("Unexpected message received: " + (msg == null ? "null" : "message class=" + msg.getClass()));
            }
        }
    }

    private boolean onClientRequestBeforeForwarding(@NonNull SaslDecodePredicate dp, Object msg) {
        frontendHandler.bufferMsg(msg);
        if (state() instanceof ProxyChannelState.ClientActive clientActive) {
            return onClientRequestInClientActiveState(dp, msg, clientActive);
        }
        else if (state() instanceof ProxyChannelState.HaProxy haProxy) {
            return onClientRequestInHaProxyState(dp, msg, haProxy);
        }
        else if (state() instanceof ProxyChannelState.ApiVersions apiVersions) {
            return onClientRequestInApiVersionsState(dp, msg, apiVersions);
        }
        else if (state() instanceof ProxyChannelState.SelectingServer) {
            return msg instanceof RequestFrame;
        }
        else {
            return state() instanceof ProxyChannelState.Connecting && msg instanceof RequestFrame;
        }
    }

    @SuppressWarnings("java:S1172")
    // We keep dp as we should need it and it gives consistency with the other onClientRequestIn methods (sue me)
    private boolean onClientRequestInApiVersionsState(@NonNull SaslDecodePredicate dp, Object msg, ProxyChannelState.ApiVersions apiVersions) {
        if (msg instanceof RequestFrame) {
            // TODO if dp.isAuthenticationOffloadEnabled() then we need to forward to that handler
            // TODO we only do the connection once we know the authenticated identity
            toSelectingServer(apiVersions.toSelectingServer());
            return true;
        }
        return false;
    }

    private boolean onClientRequestInHaProxyState(@NonNull SaslDecodePredicate dp, Object msg, ProxyChannelState.HaProxy haProxy) {
        return transitionClientRequest(dp, msg, haProxy::toApiVersions, haProxy::toSelectingServer);
    }

    private boolean transitionClientRequest(
                                            @NonNull SaslDecodePredicate dp,
                                            Object msg,
                                            Function<DecodedRequestFrame<ApiVersionsRequestData>, ProxyChannelState.ApiVersions> apiVersionsFactory,
                                            Function<DecodedRequestFrame<ApiVersionsRequestData>, ProxyChannelState.SelectingServer> selectingServerFactory) {
        if (isMessageApiVersionsRequest(msg)) {
            // We know it's an API Versions request even if the compiler doesn't
            @SuppressWarnings("unchecked")
            DecodedRequestFrame<ApiVersionsRequestData> apiVersionsFrame = (DecodedRequestFrame<ApiVersionsRequestData>) msg;
            if (dp.isAuthenticationOffloadEnabled()) {
                toApiVersions(apiVersionsFactory.apply(apiVersionsFrame), apiVersionsFrame);
            }
            else {
                toSelectingServer(selectingServerFactory.apply(apiVersionsFrame));
            }
            return true;
        }
        else if (msg instanceof RequestFrame) {
            toSelectingServer(selectingServerFactory.apply(null));
            return true;
        }
        return false;
    }

    private boolean onClientRequestInClientActiveState(@NonNull SaslDecodePredicate dp, Object msg, ProxyChannelState.ClientActive clientActive) {
        if (msg instanceof HAProxyMessage haProxyMessage) {
            toHaProxy(clientActive.toHaProxy(haProxyMessage));
            return true;
        }
        else {
            return transitionClientRequest(dp, msg, clientActive::toApiVersions, clientActive::toSelectingServer);
        }
    }

    private void toHaProxy(ProxyChannelState.HaProxy haProxy) {
        setState(haProxy);
    }

    private void toApiVersions(ProxyChannelState.ApiVersions apiVersions,
                               DecodedRequestFrame<ApiVersionsRequestData> apiVersionsFrame) {
        setState(apiVersions);
        Objects.requireNonNull(frontendHandler).inApiVersions(apiVersionsFrame);
    }

    private void toSelectingServer(ProxyChannelState.SelectingServer selectingServer) {
        setState(selectingServer);
        Objects.requireNonNull(frontendHandler).inSelectingServer();
    }

    void assertIsConnecting(String msg) {
        if (!(state instanceof ProxyChannelState.Connecting)) {
            illegalState(msg);
        }
    }

    void assertIsSelectingServer() {
        if (!(state instanceof ProxyChannelState.SelectingServer)) {
            illegalState(KafkaProxyFrontendHandler.NET_FILTER_INVOKED_IN_WRONG_STATE);
        }
    }

    void onServerInactive() {
        toClosed(null);
    }

    void serverReadComplete() {
        Objects.requireNonNull(frontendHandler).flushToClient();
    }

    void clientReadComplete() {
        if (state instanceof Forwarding) {
            Objects.requireNonNull(backendHandler).flushToServer();
        }
    }

    private void toClosed(@Nullable Throwable errorCodeEx) {
        if (state instanceof Closed) {
            return;
        }
        setState(new Closed());
        // Close the server connection
        if (backendHandler != null) {
            backendHandler.inClosed();
        }

        // Close the client connection with any error code
        Objects.requireNonNull(frontendHandler).inClosed(errorCodeEx);
    }

    void onServerException(Throwable cause) {
        LOGGER.atWarn()
                .setCause(LOGGER.isDebugEnabled() ? cause : null)
                .addArgument(cause != null ? cause.getMessage() : "")
                .log("Exception from the server channel: {}. Increase log level to DEBUG for stacktrace");
        toClosed(cause);
    }

    void onClientException(Throwable cause, boolean tlsEnabled) {
        ApiException errorCodeEx;
        if (cause instanceof DecoderException de
                && de.getCause() instanceof FrameOversizedException e) {
            var tlsHint = tlsEnabled ? "" : " or an unexpected TLS handshake";
            LOGGER.warn(
                    "Received over-sized frame from the client, max frame size bytes {}, received frame size bytes {} "
                            + "(hint: are we decoding a Kafka frame, or something unexpected like an HTTP request{}?)",
                    e.getMaxFrameSizeBytes(), e.getReceivedFrameSizeBytes(), tlsHint);
            errorCodeEx = Errors.INVALID_REQUEST.exception();
        }
        else {
            LOGGER.atWarn()
                    .setCause(LOGGER.isDebugEnabled() ? cause : null)
                    .addArgument(cause != null ? cause.getMessage() : "")
                    .log("Exception from the client channel: {}. Increase log level to DEBUG for stacktrace");
            errorCodeEx = Errors.UNKNOWN_SERVER_ERROR.exception();
        }
        toClosed(errorCodeEx);
    }

    void onClientInactive() {
        toClosed(null);
    }

    private void setState(@NonNull ProxyChannelState state) {
        LOGGER.trace("{} transitioning to {}", this, state);
        this.state = state;
    }

    private static boolean isMessageApiVersionsRequest(Object msg) {
        return msg instanceof DecodedRequestFrame
                && ((DecodedRequestFrame<?>) msg).apiKey() == ApiKeys.API_VERSIONS;
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kroxylicious.proxy.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.kroxylicious.proxy.codec.Correlation;
import io.kroxylicious.proxy.codec.KafkaRequestEncoder;
import io.kroxylicious.proxy.codec.KafkaResponseDecoder;
import io.kroxylicious.proxy.filter.KrpcRequestFilter;
import io.kroxylicious.proxy.filter.KrpcResponseFilter;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;

public class KafkaProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LogManager.getLogger(KafkaProxyFrontendHandler.class);

    /**
     * Builds a request pipeline for incoming requests from the downstream client.
     * @param filters The filters in the pipeline (response filters won't be added to the result).
     * @return A list of channel handlers
     */
    static List<ChannelHandler> buildRequestPipeline(List<KrpcRequestFilter> filters) {
        // Note: we could equally use a single ChannelInboundHandler which itself dispatched to each filter.
        // Using a ChannelInboundHandler-per-filter model means that we're not occupying the CPU for the
        // whole filterchain execution => higher latency, but higher throughput.
        List<ChannelHandler> requestFilterHandlers = new ArrayList<>(filters.size());
        for (var requestFilter : filters) {
            requestFilterHandlers.add(new SingleRequestFilterHandler(requestFilter));
        }
        Collections.reverse(requestFilterHandlers);
        return requestFilterHandlers;
    }

    /**
     * Builds a response pipeline for incomping responses from the upstream server.
     * @param filters The filters in the pipeline (request filters won't be added to the result).
     * @return A list of channel handlers
     */
    static List<ChannelHandler> buildResponsePipeline(List<KrpcResponseFilter> filters) {
        // Note: we could equally use a single ChannelInboundHandler which itself dispatched to each filter.
        // Using a ChannelInboundHandler-per-filter model means that we're not occupying the CPU for the
        // whole filterchain execution => higher latency, but higher throughput.
        List<ChannelHandler> responseFilterHandlers = new ArrayList<>(filters.size());
        for (var responseFilter : filters) {
            responseFilterHandlers.add(new SingleResponseFilterHandler(responseFilter));
        }
        return responseFilterHandlers;
    }

    private final String remoteHost;
    private final int remotePort;
    private final Map<Integer, Correlation> correlation;
    private final boolean logNetwork;
    private final boolean logFrames;
    private final List<ChannelHandler> responseFilterHandlers;
    private final List<ChannelHandler> requestFilterHandlers;

    private ChannelHandlerContext outboundCtx;
    private KafkaProxyBackendHandler backendHandler;
    private boolean pendingFlushes;
    private ChannelHandlerContext blockedInboundCtx;

    public KafkaProxyFrontendHandler(String remoteHost,
                                     int remotePort,
                                     Map<Integer, Correlation> correlation,
                                     List<KrpcRequestFilter> requestFilters,
                                     List<KrpcResponseFilter> responseFilters,
                                     boolean logNetwork,
                                     boolean logFrames) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.correlation = correlation;
        this.logNetwork = logNetwork;
        this.logFrames = logFrames;
        this.responseFilterHandlers = buildResponsePipeline(responseFilters);
        this.requestFilterHandlers = buildRequestPipeline(requestFilters);
    }

    public void outboundChannelActive(ChannelHandlerContext ctx) {
        outboundCtx = ctx;
    }

    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
        // this is key to propagate back-pressure changes
        if (backendHandler != null) {
            backendHandler.inboundChannelWritabilityChanged(ctx);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        LOGGER.trace("Channel active {}", ctx);
        final Channel inboundChannel = ctx.channel();

        // Start the upstream connection attempt.
        Bootstrap b = new Bootstrap();
        backendHandler = new KafkaProxyBackendHandler(this, ctx);
        b.group(inboundChannel.eventLoop())
                .channel(ctx.channel().getClass())
                .handler(backendHandler)
                .option(ChannelOption.AUTO_READ, true)
                .option(ChannelOption.TCP_NODELAY, true);

        LOGGER.trace("Connecting to outbound {}:{}", remoteHost, remotePort);
        ChannelFuture connectFuture = b.connect(remoteHost, remotePort);

        List<ChannelHandler> handlers = new ArrayList<>();
        if (logNetwork) {
            handlers.add(new LoggingHandler("backend-network"));
        }
        handlers.add(new KafkaRequestEncoder());
        handlers.addAll(requestFilterHandlers);

        handlers.add(new KafkaResponseDecoder(correlation));
        handlers.addAll(responseFilterHandlers);

        if (logFrames) {
            handlers.add(new LoggingHandler("backend-application"));
        }
        Collections.reverse(handlers);
        Channel outboundChannel = connectFuture.channel();
        ChannelPipeline pipeline = outboundChannel.pipeline();
        for (var handler : handlers) {
            pipeline.addFirst(handler);
        }

        connectFuture.addListener(future -> {
            if (future.isSuccess()) {
                LOGGER.trace("Outbound connect complete ({}), register interest to read on inbound channel {}", outboundChannel.localAddress(), inboundChannel);
                // connection complete start to read first data
                inboundChannel.config().setAutoRead(true);
            }
            else {
                // Close the connection if the connection attempt has failed.
                LOGGER.trace("Outbound connect error, closing inbound channel", future.cause());
                inboundChannel.close();
            }
        });
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        LOGGER.trace("Completed read on inbound channel: {}", msg);
        if (outboundCtx == null) {
            LOGGER.trace("Outbound is not active");
            return;
        }
        final Channel outboundChannel = outboundCtx.channel();
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Outbound writable: {}", outboundChannel.isWritable());
            LOGGER.trace("Outbound bytesBeforeUnwritable: {}", outboundChannel.bytesBeforeUnwritable());
            LOGGER.trace("Outbound config: {}", outboundChannel.config());
            LOGGER.trace("Outbound is active, writing and flushing {}", msg);
        }
        if (outboundChannel.isWritable()) {
            outboundChannel.write(msg, outboundCtx.voidPromise());
            pendingFlushes = true;
        }
        else {
            outboundChannel.writeAndFlush(msg, outboundCtx.voidPromise());
            pendingFlushes = false;
        }
    }

    public void outboundWritabilityChanged(ChannelHandlerContext outboundCtx) {
        assert this.outboundCtx == outboundCtx;
        final ChannelHandlerContext inboundCtx = blockedInboundCtx;
        if (inboundCtx != null && outboundCtx.channel().isWritable()) {
            blockedInboundCtx = null;
            inboundCtx.channel().config().setAutoRead(true);
        }
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
        if (outboundCtx == null) {
            LOGGER.trace("Outbound is not active");
            return;
        }
        final Channel outboundChannel = outboundCtx.channel();
        if (pendingFlushes) {
            pendingFlushes = false;
            outboundChannel.flush();
        }
        if (!outboundChannel.isWritable()) {
            ctx.channel().config().setAutoRead(false);
            this.blockedInboundCtx = ctx;
        }

    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundCtx == null) {
            return;
        }
        final Channel outboundChannel = outboundCtx.channel();
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        closeOnFlush(ctx.channel());
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

}

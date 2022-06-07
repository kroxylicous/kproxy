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

import io.kroxylicious.proxy.codec.DecodedRequestFrame;
import io.kroxylicious.proxy.filter.KrpcRequestFilter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * A {@code ChannelInboundHandler} (for handling requests from downstream)
 * that applies a single {@link KrpcRequestFilter}.
 */
public class SingleRequestFilterHandler extends ChannelOutboundHandlerAdapter {

    private final KrpcRequestFilter filter;

    private ChannelHandlerContext channelContext;

    public SingleRequestFilterHandler(KrpcRequestFilter filter) {
        this.filter = filter;
    }

    public KrpcRequestFilter filter() {
        return filter;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.channelContext = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof DecodedRequestFrame) {
            DecodedRequestFrame<?> decodedFrame = (DecodedRequestFrame<?>) msg;
            // Guarding against invoking the filter unexpectely
            if (filter.shouldDeserializeRequest(decodedFrame.apiKey(), decodedFrame.apiVersion())) {
                var filterContext = new DefaultFilterContext(channelContext, decodedFrame);
                switch (filter.apply(decodedFrame, filterContext)) {
                    case FORWARD:
                        super.write(ctx, msg, promise);
                        break;
                    case DROP:
                        break;
                    case DISCONNECT:
                        ctx.disconnect();
                }

            }
            else {
                super.write(ctx, msg, promise);
            }
        }
        else {
            super.write(ctx, msg, promise);
        }
    }

}

/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.Cancellable;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.ListenableAsyncCloseable;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.EmptyHttpHeaders;
import io.servicetalk.http.api.HttpPayloadChunk;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpRequestMetaData;
import io.servicetalk.http.api.HttpRequestMethod;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpService;
import io.servicetalk.http.api.LastHttpPayloadChunk;
import io.servicetalk.tcp.netty.internal.TcpServerChannelInitializer;
import io.servicetalk.tcp.netty.internal.TcpServerInitializer;
import io.servicetalk.transport.api.ConnectionContext;
import io.servicetalk.transport.api.ContextFilter;
import io.servicetalk.transport.api.FlushStrategy;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.netty.internal.AbstractChannelReadHandler;
import io.servicetalk.transport.netty.internal.ChannelInitializer;
import io.servicetalk.transport.netty.internal.Connection;
import io.servicetalk.transport.netty.internal.Connection.TerminalPredicate;
import io.servicetalk.transport.netty.internal.NettyConnection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import static io.servicetalk.buffer.api.EmptyBuffer.EMPTY_BUFFER;
import static io.servicetalk.concurrent.api.AsyncCloseables.newCompositeCloseable;
import static io.servicetalk.concurrent.api.AsyncCloseables.toAsyncCloseable;
import static io.servicetalk.concurrent.api.Completable.completed;
import static io.servicetalk.concurrent.api.Publisher.just;
import static io.servicetalk.concurrent.api.Single.success;
import static io.servicetalk.http.api.HttpHeaderNames.CONTENT_LENGTH;
import static io.servicetalk.http.api.HttpHeaderValues.ZERO;
import static io.servicetalk.http.api.HttpPayloadChunks.newLastPayloadChunk;
import static io.servicetalk.http.api.HttpRequests.newRequest;
import static io.servicetalk.http.api.HttpResponseStatuses.INTERNAL_SERVER_ERROR;
import static io.servicetalk.http.api.HttpResponses.newResponse;
import static io.servicetalk.http.netty.HeaderUtils.addResponseTransferEncodingIfNecessary;
import static io.servicetalk.http.netty.SpliceFlatStreamToMetaSingle.flatten;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class NettyHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyHttpServer.class);
    private static final Predicate<Object> LAST_HTTP_PAYLOAD_CHUNK_OBJECT_PREDICATE =
            p -> p instanceof LastHttpPayloadChunk;
    private static final Predicate<HttpPayloadChunk> LAST_HTTP_PAYLOAD_CHUNK_PREDICATE =
            p -> p instanceof LastHttpPayloadChunk;

    private NettyHttpServer() {
        // No instances
    }

    static Single<ServerContext> bind(final ReadOnlyHttpServerConfig config, final SocketAddress address,
                                      final ContextFilter contextFilter, final Executor executor,
                                      final HttpService<HttpPayloadChunk, HttpPayloadChunk> service) {
        final TcpServerInitializer initializer = new TcpServerInitializer(config.getTcpConfig());

        final ChannelInitializer channelInitializer = new TcpServerChannelInitializer(config.getTcpConfig())
                .andThen(getChannelInitializer(config, contextFilter, executor, service));

        // The ServerContext returned by TcpServerInitializer takes care of closing the contextFilter.
        return initializer.start(address, contextFilter, channelInitializer, false)
                .map((ServerContext delegate) -> new NettyHttpServerContext(delegate, service, executor));
    }

    private static ChannelInitializer getChannelInitializer(
            final ReadOnlyHttpServerConfig config, final ContextFilter contextFilter, final Executor executor,
            final HttpService<HttpPayloadChunk, HttpPayloadChunk> service) {
        return (channel, context) -> {

            // TODO: Context filtering should be moved somewhere central. Maybe TcpServerInitializer.start?
            final Single<Boolean> filterResultSingle = contextFilter.filter(context);
            filterResultSingle.subscribe(new io.servicetalk.concurrent.Single.Subscriber<Boolean>() {
                @Override
                public void onSubscribe(final Cancellable cancellable) {
                    // Don't need to do anything.
                }

                @Override
                public void onSuccess(@Nullable final Boolean result) {
                    if (result != null && result) {
                        // Getting the remote-address may involve volatile reads and potentially a syscall, so guard it.
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Accepted connection from {}", context.getRemoteAddress());
                        }
                        handleAcceptedConnection(config, executor, service, channel, context);
                    } else {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Rejected connection from {}", context.getRemoteAddress());
                        }
                        handleRejectedConnection(context);
                    }
                }

                @Override
                public void onError(final Throwable t) {
                    LOGGER.warn("Context filter threw exception.", t);
                    handleRejectedConnection(context);
                }
            });

            return context;
        };
    }

    private static void handleRejectedConnection(final ConnectionContext context) {
        context.closeAsync().subscribe();
    }

    private static void handleAcceptedConnection(final ReadOnlyHttpServerConfig config, final Executor executor,
                                                 final HttpService<HttpPayloadChunk, HttpPayloadChunk> service,
                                                 final Channel channel, final ConnectionContext context) {
        channel.pipeline().addLast(new HttpRequestDecoder(config.getHeadersFactory(),
                config.getMaxInitialLineLength(), config.getMaxHeaderSize(), config.getMaxChunkSize(), true));
        channel.pipeline().addLast(new HttpResponseEncoder(config.getHeadersEncodedSizeEstimate(),
                config.getTrailersEncodedSizeEstimate()));
        channel.pipeline().addLast(new AbstractChannelReadHandler<Object>(LAST_HTTP_PAYLOAD_CHUNK_OBJECT_PREDICATE,
                executor) {
            @Override
            protected void onPublisherCreation(final ChannelHandlerContext channelHandlerContext,
                                               final Publisher<Object> requestObjectPublisher) {
                final NettyConnection<Object, Object> conn = new NettyConnection<>(
                        channelHandlerContext.channel(),
                        context,
                        requestObjectPublisher,
                        new TerminalPredicate<>(LAST_HTTP_PAYLOAD_CHUNK_OBJECT_PREDICATE));
                final Publisher<Object> connRequestObjectPublisher = conn.read();
                final Single<HttpRequest<HttpPayloadChunk>> requestSingle =
                 new SpliceFlatStreamToMetaSingle<HttpRequest<HttpPayloadChunk>, HttpRequestMetaData, HttpPayloadChunk>(
                                connRequestObjectPublisher,
                                (hr, pub) -> newRequest(
                                        hr.getVersion(), hr.getMethod(), hr.getRequestTarget(), pub, hr.getHeaders()));
                handleRequestAndWriteResponse(executor, service, conn, context, requestSingle).subscribe();
            }
        });
    }

    private static Completable handleRequestAndWriteResponse(
            final Executor executor, final HttpService<HttpPayloadChunk, HttpPayloadChunk> service,
            final NettyConnection<Object, Object> conn, final ConnectionContext context,
            final Single<HttpRequest<HttpPayloadChunk>> requestSingle) {
        final Publisher<Object> responseObjectPublisher = requestSingle.flatMapPublisher(request -> {
            final HttpRequestMethod requestMethod = request.getMethod();
            final HttpKeepAlive keepAlive = HttpKeepAlive.getResponseKeepAlive(request);
            final Completable drainRequestPayloadBody = request.getPayloadBody().ignoreElements().onErrorResume(
                    t -> completed()
                    /* ignore error from SpliceFlatStreamToMetaSingle about duplicate subscriptions. */);

            return handleRequest(service, context, request)
                    .map(response -> processResponse(requestMethod, keepAlive, drainRequestPayloadBody, response))
                    .flatMapPublisher(resp -> flatten(executor, resp, HttpResponse::getPayloadBody))
                    .concatWith(keepAlive.closeConnectionIfNecessary(executor.schedule(100, MILLISECONDS).andThen(conn.closeAsyncDeferred())));
        });
        return writeResponse(conn, responseObjectPublisher.repeat(val -> true));
    }

    private static Single<HttpResponse<HttpPayloadChunk>> handleRequest(
            final HttpService<HttpPayloadChunk, HttpPayloadChunk> service, final ConnectionContext context,
            final HttpRequest<HttpPayloadChunk> request) {
        try {
            return service.handle(context, request)
                    .onErrorResume(cause -> newErrorResponse(service, context, cause, request));
        } catch (final Throwable cause) {
            return newErrorResponse(service, context, cause, request);
        }
    }

    private static HttpResponse<HttpPayloadChunk> processResponse(final HttpRequestMethod requestMethod,
                                                                  final HttpKeepAlive keepAlive,
                                                                  final Completable drainRequestPayloadBody,
                                                                  final HttpResponse<HttpPayloadChunk> response) {
        addResponseTransferEncodingIfNecessary(response, requestMethod);
        keepAlive.addConnectionHeaderIfNecessary(response);

        // When the response payload publisher completes, read any of the request payload that hasn't already
        // been read. This is necessary for using a persistent connection to send multiple requests.
        return response.transformPayloadBody(responsePayload -> ensureLastPayloadChunk(responsePayload)
                .concatWith(drainRequestPayloadBody));
    }

    private static Publisher<HttpPayloadChunk> ensureLastPayloadChunk(
            final Publisher<HttpPayloadChunk> responseObjectPublisher) {
        return responseObjectPublisher.liftSynchronous(new EnsureLastItemBeforeCompleteOperator<>(
                LAST_HTTP_PAYLOAD_CHUNK_PREDICATE,
                () -> EmptyLastHttpPayloadChunk.INSTANCE));
    }

    private static Single<HttpResponse<HttpPayloadChunk>> newErrorResponse(
            final HttpService<HttpPayloadChunk, HttpPayloadChunk> service, final ConnectionContext context,
            final Throwable cause, final HttpRequest<HttpPayloadChunk> request) {
        LOGGER.error("internal server error service={} connection={}", service, context, cause);
        final HttpResponse<HttpPayloadChunk> response = newResponse(request.getVersion(), INTERNAL_SERVER_ERROR,
                // Using immediate is OK here because the user will never touch this response and it will
                // only be consumed by ServiceTalk at this point.
                just(newLastPayloadChunk(EMPTY_BUFFER, EmptyHttpHeaders.INSTANCE)));
        response.getHeaders().set(CONTENT_LENGTH, ZERO);
        return success(response);
    }

    private static Completable writeResponse(
            final Connection<Object, Object> conn, final Publisher<Object> responseObjectPublisher) {
        return conn.write(responseObjectPublisher, FlushStrategy.flushOnEach());
    }

    private static final class NettyHttpServerContext implements ServerContext {

        private final ServerContext delegate;
        private final ListenableAsyncCloseable asyncCloseable;

        NettyHttpServerContext(final ServerContext delegate, final HttpService service, final Executor executor) {
            this.delegate = delegate;
            asyncCloseable = toAsyncCloseable(() -> newCompositeCloseable().concat(service, delegate, executor).closeAsync());
        }

        @Override
        public SocketAddress getListenAddress() {
            return delegate.getListenAddress();
        }

        @Override
        public Completable closeAsync() {
            return asyncCloseable.closeAsync();
        }

        @Override
        public Completable onClose() {
            return asyncCloseable.onClose();
        }
    }
}

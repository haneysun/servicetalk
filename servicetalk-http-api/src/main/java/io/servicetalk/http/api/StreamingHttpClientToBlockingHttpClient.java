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
package io.servicetalk.http.api;

import io.servicetalk.concurrent.BlockingIterable;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.http.api.StreamingHttpClient.ReservedStreamingHttpConnection;
import io.servicetalk.http.api.StreamingHttpConnection.SettingKey;
import io.servicetalk.transport.api.ConnectionContext;
import io.servicetalk.transport.api.ExecutionContext;

import static io.servicetalk.http.api.BlockingUtils.blockingInvocation;
import static io.servicetalk.http.api.HttpExecutionStrategies.OFFLOAD_NONE_STRATEGY;
import static java.util.Objects.requireNonNull;

final class StreamingHttpClientToBlockingHttpClient extends BlockingHttpClient {
    private final StreamingHttpClient client;

    private StreamingHttpClientToBlockingHttpClient(final StreamingHttpClient client,
                                                    final HttpExecutionStrategy strategy) {
        super(new StreamingHttpRequestResponseFactoryToHttpRequestResponseFactory(client.reqRespFactory), strategy);
        this.client = requireNonNull(client);
    }

    @Override
    public ReservedBlockingHttpConnection reserveConnection(final HttpExecutionStrategy strategy,
                                                            final HttpRequestMetaData metaData) throws Exception {
        return blockingInvocation(client.reserveConnection(strategy, metaData)
                .map(c -> new ReservedStreamingHttpConnectionToBlocking(c, executionStrategy())));
    }

    @Override
    public HttpResponse request(final HttpExecutionStrategy strategy, final HttpRequest request) throws Exception {
        return BlockingUtils.request(client, strategy, request);
    }

    @Override
    public ExecutionContext executionContext() {
        return client.executionContext();
    }

    @Override
    public void close() throws Exception {
        blockingInvocation(client.closeAsync());
    }

    static BlockingHttpClient transform(StreamingHttpClient client) {
        final HttpExecutionStrategy defaultStrategy = client instanceof StreamingHttpClientFilter ?
                ((StreamingHttpClientFilter) client).effectiveExecutionStrategy(OFFLOAD_NONE_STRATEGY) :
                OFFLOAD_NONE_STRATEGY;
        return new StreamingHttpClientToBlockingHttpClient(client, defaultStrategy);
    }

    @Override
    StreamingHttpClient asStreamingClientInternal() {
        return client;
    }

    Completable onClose() {
        return client.onClose();
    }

    static final class ReservedStreamingHttpConnectionToBlocking extends ReservedBlockingHttpConnection {
        private final ReservedStreamingHttpConnection connection;

        private ReservedStreamingHttpConnectionToBlocking(ReservedStreamingHttpConnection connection,
                                                          HttpExecutionStrategy strategy) {
            super(new StreamingHttpRequestResponseFactoryToHttpRequestResponseFactory(connection.reqRespFactory),
                    strategy);
            this.connection = requireNonNull(connection);
        }

        @Override
        public void release() throws Exception {
            blockingInvocation(connection.releaseAsync());
        }

        @Override
        public ConnectionContext connectionContext() {
            return connection.connectionContext();
        }

        @Override
        public <T> BlockingIterable<T> settingIterable(final SettingKey<T> settingKey) {
            return connection.settingStream(settingKey).toIterable();
        }

        @Override
        public HttpResponse request(final HttpExecutionStrategy strategy, final HttpRequest request) throws Exception {
            return blockingInvocation(connection.request(strategy, request.toStreamingRequest())
                    .flatMap(StreamingHttpResponse::toResponse));
        }

        @Override
        public ExecutionContext executionContext() {
            return connection.executionContext();
        }

        @Override
        public void close() throws Exception {
            blockingInvocation(connection.closeAsync());
        }

        @Override
        ReservedStreamingHttpConnection asStreamingConnectionInternal() {
            return connection;
        }

        Completable onClose() {
            return connection.onClose();
        }

        static ReservedBlockingHttpConnection transform(ReservedStreamingHttpConnection conn) {
            final HttpExecutionStrategy defaultStrategy = conn instanceof ReservedStreamingHttpConnectionFilter ?
                    ((ReservedStreamingHttpConnectionFilter) conn).effectiveExecutionStrategy(OFFLOAD_NONE_STRATEGY) :
                    OFFLOAD_NONE_STRATEGY;
            return new ReservedStreamingHttpConnectionToBlocking(conn, defaultStrategy);
        }
    }
}
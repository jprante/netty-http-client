package org.xbib.netty.http.client;

import org.jboss.netty.bootstrap.Bootstrap;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelConfig;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.socket.SocketChannel;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioSocketChannel;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.ssl.OpenSsl;
import org.jboss.netty.handler.ssl.SslHandler;
import org.xbib.netty.http.client.handler.http.HttpChannelInitializer;
import org.xbib.netty.http.client.handler.http2.Http2ChannelInitializer;
import org.xbib.netty.http.client.pool.BoundedChannelPool;
import org.xbib.netty.http.client.transport.Http2Transport;
import org.xbib.netty.http.client.transport.HttpTransport;
import org.xbib.netty.http.client.transport.Transport;
import org.xbib.netty.http.common.HttpAddress;
import org.xbib.netty.http.common.NetworkUtils;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Client {

    private static final Logger logger = Logger.getLogger(Client.class.getName());

    private static final ThreadFactory httpClientThreadFactory = new HttpClientThreadFactory();

    static {
        if (System.getProperty("xbib.netty.http.client.extendsystemproperties") != null) {
            NetworkUtils.extendSystemProperties();
        }
        // change Netty defaults to safer ones, but still allow override from arg line
        if (System.getProperty("io.netty.noUnsafe") == null) {
            System.setProperty("io.netty.noUnsafe", Boolean.toString(true));
        }
        if (System.getProperty("io.netty.noKeySetOptimization") == null) {
            System.setProperty("io.netty.noKeySetOptimization", Boolean.toString(true));
        }
    }

    private final ClientConfig clientConfig;

    private final ClientBootstrap bootstrap;

    private final List<Transport> transports;

    private BoundedChannelPool<HttpAddress> pool;

    public Client() {
        this(new ClientConfig());
    }

    public Client(ClientConfig clientConfig) {
        Objects.requireNonNull(clientConfig);
        this.clientConfig = clientConfig;
        initializeTrustManagerFactory(clientConfig);
        ChannelFactory channelFactory = new NioClientSocketChannelFactory();
        this.bootstrap = new ClientBootstrap(channelFactory);
        bootstrap.setOption("tcpNoDelay", clientConfig.isTcpNodelay());
        bootstrap.setOption("keepAlive", clientConfig.isKeepAlive());
        bootstrap.setOption("reuseAddr", clientConfig.isReuseAddr());
        bootstrap.setOption("sendBufferSize", clientConfig.getTcpSendBufferSize());
        bootstrap.setOption("receiveBufferSize", clientConfig.getTcpReceiveBufferSize());
        this.transports = new CopyOnWriteArrayList<>();
        if (!clientConfig.getPoolNodes().isEmpty()) {
            List<HttpAddress> nodes = clientConfig.getPoolNodes();
            Integer limit = clientConfig.getPoolNodeConnectionLimit();
            if (limit == null || limit < 1) {
                limit = 1;
            }
            Semaphore semaphore = new Semaphore(limit);
            Integer retries = clientConfig.getRetriesPerPoolNode();
            if (retries == null || retries < 0) {
                retries = 0;
            }
            ClientChannelPoolHandler clientChannelPoolHandler = new ClientChannelPoolHandler();
            this.pool = new BoundedChannelPool<>(semaphore, clientConfig.getPoolVersion(),
                    nodes, bootstrap, clientChannelPoolHandler, retries,
                    BoundedChannelPool.PoolKeySelectorType.ROUNDROBIN);
            Integer nodeConnectionLimit = clientConfig.getPoolNodeConnectionLimit();
            if (nodeConnectionLimit == null || nodeConnectionLimit == 0) {
                nodeConnectionLimit = nodes.size();
            }
            try {
                this.pool.prepare(nodeConnectionLimit);
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    public static ClientBuilder builder() {
        return new ClientBuilder();
    }

    public ClientConfig getClientConfig() {
        return clientConfig;
    }

    public boolean hasPooledConnections() {
        return pool != null && !clientConfig.getPoolNodes().isEmpty();
    }

    public void logDiagnostics(Level level) {
        logger.log(level, () -> "OpenSSL available: " + OpenSsl.isAvailable() +
                " Local host name: " + NetworkUtils.getLocalHostName("localhost"));
        logger.log(level, NetworkUtils::displayNetworkInterfaces);
    }

    public Transport newTransport() {
        return newTransport(null);
    }

    public Transport newTransport(HttpAddress httpAddress) {
        Transport transport;
        if (httpAddress != null) {
            if (httpAddress.getVersion().majorVersion() == 1) {
                transport = new HttpTransport(this, httpAddress);
            } else {
                transport = new Http2Transport(this, httpAddress);
            }
        } else if (hasPooledConnections()) {
            if (pool.getVersion().majorVersion() == 1) {
                transport = new HttpTransport(this, null);
            } else {
                transport = new Http2Transport(this, null);
            }
        } else {
            throw new IllegalStateException("no address given to connect to");
        }
        transports.add(transport);
        return transport;
    }

    public Channel newChannel(HttpAddress httpAddress) throws IOException {
        Channel channel;
        if (httpAddress != null) {
            HttpVersion httpVersion = httpAddress.getVersion();
            ChannelInitializer<Channel> initializer;
            SslHandler sslHandler = newSslHandler(clientConfig, byteBufAllocator, httpAddress);
            if (httpVersion.getMajorVersion() == 1) {
                initializer = new HttpChannelInitializer(clientConfig, httpAddress, sslHandler,
                        new Http2ChannelInitializer(clientConfig, httpAddress, sslHandler));
            } else {
                initializer = new Http2ChannelInitializer(clientConfig, httpAddress, sslHandler);
            }
            try {
                channel = bootstrap.handler(initializer)
                        .connect(httpAddress.getInetSocketAddress()).sync().await().channel();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        } else {
            if (hasPooledConnections()) {
                try {
                    channel = pool.acquire();
                } catch (Exception e) {
                    throw new IOException(e);
                }
            } else {
                throw new UnsupportedOperationException();
            }
        }
        return channel;
    }

    public void releaseChannel(Channel channel, boolean close) throws IOException{
        if (channel == null) {
            return;
        }
        if (hasPooledConnections()) {
            try {
                pool.release(channel, close);
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else if (close) {
           channel.close();
        }
    }

    public Transport execute(Request request) throws IOException {
        Transport transport = newTransport(HttpAddress.of(request.url(), request.httpVersion()));
        transport.execute(request);
        return transport;
    }

    public <T> CompletableFuture<T> execute(Request request,
                                            Function<FullHttpResponse, T> supplier) throws IOException {
        return newTransport(HttpAddress.of(request.url(), request.httpVersion()))
                .execute(request, supplier);
    }

    /**
     * For following redirects, construct a new transport.
     * @param transport the previous transport
     * @param request the new request for continuing the request.
     * @throws IOException if continuation fails
     */
    public void continuation(Transport transport, Request request) throws IOException {
        Transport nextTransport = newTransport(HttpAddress.of(request.url(), request.httpVersion()));
        nextTransport.setCookieBox(transport.getCookieBox());
        nextTransport.execute(request);
        nextTransport.get();
        close(nextTransport);
    }

    /**
     * Retry request by following a back-off strategy.
     *
     * @param transport the transport to retry
     * @param request the request to retry
     * @throws IOException if retry failed
     */
    public void retry(Transport transport, Request request) throws IOException {
        transport.execute(request);
        transport.get();
        close(transport);
    }

    public void close(Transport transport) throws IOException {
        transport.close();
        transports.remove(transport);
    }

    public void close() throws IOException {
        for (Transport transport : transports) {
            close(transport);
        }
        // how to wait for all responses for the pool?
        if (hasPooledConnections()) {
            pool.close();
        }
    }

    public void shutdownGracefully() throws IOException {
        close();
        shutdown();
    }

    public void shutdown() {
        eventLoopGroup.shutdownGracefully();
        try {
            eventLoopGroup.awaitTermination(10L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    /**
     * Initialize trust manager factory once per client lifecycle.
     * @param clientConfig the client config
     */
    private static void initializeTrustManagerFactory(ClientConfig clientConfig) {
        TrustManagerFactory trustManagerFactory = clientConfig.getTrustManagerFactory();
        if (trustManagerFactory != null) {
            try {
                trustManagerFactory.init(clientConfig.getTrustManagerKeyStore());
            } catch (KeyStoreException e) {
                logger.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }

    private static SslHandler newSslHandler(ClientConfig clientConfig, ByteBufAllocator allocator, HttpAddress httpAddress) {
        try {
            SslContext sslContext = newSslContext(clientConfig, httpAddress.getVersion());
            InetSocketAddress peer = httpAddress.getInetSocketAddress();
            SslHandler sslHandler = sslContext.newHandler(allocator, peer.getHostName(), peer.getPort());
            SSLEngine engine = sslHandler.engine();
            List<String> serverNames = clientConfig.getServerNamesForIdentification();
            if (serverNames.isEmpty()) {
                serverNames = Collections.singletonList(peer.getHostName());
            }
            SSLParameters params = engine.getSSLParameters();
            // use sslContext.newHandler(allocator, peerHost, peerPort) when using params.setEndpointIdentificationAlgorithm
            params.setEndpointIdentificationAlgorithm("HTTPS");
            List<SNIServerName> sniServerNames = new ArrayList<>();
            for (String serverName : serverNames) {
                sniServerNames.add(new SNIHostName(serverName));
            }
            params.setServerNames(sniServerNames);
            engine.setSSLParameters(params);
            switch (clientConfig.getClientAuthMode()) {
                case NEED:
                    engine.setNeedClientAuth(true);
                    break;
                case WANT:
                    engine.setWantClientAuth(true);
                    break;
                default:
                    break;
            }
            return sslHandler;
        } catch (SSLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static SslContext newSslContext(ClientConfig clientConfig, HttpVersion httpVersion) throws SSLException {
        SslContextBuilder sslContextBuilder = SslContextBuilder.forClient()
                .sslProvider(clientConfig.getSslProvider())
                .ciphers(Http2SecurityUtil.CIPHERS, clientConfig.getCipherSuiteFilter())
                .applicationProtocolConfig(newApplicationProtocolConfig(httpVersion));
        if (clientConfig.getSslContextProvider() != null) {
            sslContextBuilder.sslContextProvider(clientConfig.getSslContextProvider());
        }
        if (clientConfig.getTrustManagerFactory() != null) {
            sslContextBuilder.trustManager(clientConfig.getTrustManagerFactory());
        }
        return sslContextBuilder.build();
    }

    private static ApplicationProtocolConfig newApplicationProtocolConfig(HttpVersion httpVersion) {
        return httpVersion.majorVersion() == 1 ?
                new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_1_1) :
                new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2);
    }

    static class HttpClientThreadFactory implements ThreadFactory {

        private int number = 0;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "org-xbib-netty-http-client-pool-" + (number++));
            thread.setDaemon(true);
            return thread;
        }
    }

    class ClientChannelPoolHandler implements ChannelPoolHandler {

        @Override
        public void channelReleased(Channel channel) {
        }

        @Override
        public void channelAcquired(Channel channel) {
        }

        @Override
        public void channelCreated(Channel channel) {
            HttpAddress httpAddress = channel.attr(pool.getAttributeKey()).get();
            HttpVersion httpVersion = httpAddress.getVersion();
            SslHandler sslHandler = newSslHandler(clientConfig, byteBufAllocator, httpAddress);
            if (httpVersion.majorVersion() == 1) {
                HttpChannelInitializer initializer = new HttpChannelInitializer(clientConfig, httpAddress, sslHandler,
                        new Http2ChannelInitializer(clientConfig, httpAddress, sslHandler));
                initializer.initChannel(channel);
            } else {
                Http2ChannelInitializer initializer = new Http2ChannelInitializer(clientConfig, httpAddress, sslHandler);
                initializer.initChannel(channel);
            }
        }
    }
}

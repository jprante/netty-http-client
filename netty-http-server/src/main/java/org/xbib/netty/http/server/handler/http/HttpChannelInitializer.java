package org.xbib.netty.http.server.handler.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.DomainNameMapping;
import org.xbib.netty.http.common.HttpAddress;
import org.xbib.netty.http.server.Server;
import org.xbib.netty.http.server.ServerConfig;
import org.xbib.netty.http.server.handler.TrafficLoggingHandler;
import org.xbib.netty.http.server.transport.ServerTransport;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final Logger logger = Logger.getLogger(HttpChannelInitializer.class.getName());

    private final Server server;

    private final ServerConfig serverConfig;

    private final HttpAddress httpAddress;

    private final HttpHandler httpHandler;

    private final SniHandler sniHandler;

    public HttpChannelInitializer(Server server,
                                  HttpAddress httpAddress,
                                  DomainNameMapping<SslContext> domainNameMapping) {
        this.server = server;
        this.serverConfig = server.getServerConfig();
        this.httpAddress = httpAddress;
        this.httpHandler = new HttpHandler(server);
        this.sniHandler = domainNameMapping != null ? new SniHandler(domainNameMapping) : null;
    }

    @Override
    public void initChannel(SocketChannel channel) {
        ServerTransport serverTransport = server.newTransport(httpAddress.getVersion());
        channel.attr(ServerTransport.TRANSPORT_ATTRIBUTE_KEY).set(serverTransport);
        if (serverConfig.isDebug()) {
            channel.pipeline().addLast(new TrafficLoggingHandler(LogLevel.DEBUG));
        }
        if (httpAddress.isSecure()) {
            configureEncrypted(channel);
        } else {
            configureCleartext(channel);
        }
        if (serverConfig.isDebug()) {
            logger.log(Level.FINE, "HTTP 1 channel initialized: " + channel.pipeline().names());
        }
    }

    private void configureEncrypted(SocketChannel channel)  {
        if (sniHandler != null) {
            channel.pipeline().addLast("sni-handker", sniHandler);
        }
        configureCleartext(channel);
    }

    private void configureCleartext(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("http-server-codec",
                new HttpServerCodec(serverConfig.getMaxInitialLineLength(),
                        serverConfig.getMaxHeadersSize(), serverConfig.getMaxChunkSize()));
        if (serverConfig.isCompressionEnabled()) {
            pipeline.addLast("http-server-compressor", new HttpContentCompressor());
        }
        if (serverConfig.isDecompressionEnabled()) {
            pipeline.addLast("http-server-decompressor", new HttpContentDecompressor());
        }
        HttpObjectAggregator httpObjectAggregator = new HttpObjectAggregator(serverConfig.getMaxContentLength(),
                false);
        httpObjectAggregator.setMaxCumulationBufferComponents(serverConfig.getMaxCompositeBufferComponents());
        pipeline.addLast("http-server-aggregator", httpObjectAggregator);
        pipeline.addLast("http-server-pipelining", new HttpPipeliningHandler(1024));
        pipeline.addLast("http-server-chunked-write", new ChunkedWriteHandler());
        pipeline.addLast(httpHandler);
    }

    @Sharable
    class HttpHandler extends ChannelInboundHandlerAdapter {

        private final Logger logger = Logger.getLogger(HttpHandler.class.getName());

        private final Server server;

        public HttpHandler(Server server) {
            this.server = server;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpPipelinedRequest) {
                HttpPipelinedRequest httpPipelinedRequest = (HttpPipelinedRequest) msg;
                if (httpPipelinedRequest.getRequest() instanceof FullHttpRequest) {
                    FullHttpRequest fullHttpRequest = (FullHttpRequest) httpPipelinedRequest.getRequest();
                    ServerTransport serverTransport = server.newTransport(fullHttpRequest.protocolVersion());
                    serverTransport.requestReceived(ctx, fullHttpRequest, httpPipelinedRequest.getSequenceId());
                }
            } else {
                super.channelRead(ctx, msg);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.log(Level.WARNING, cause.getMessage(), cause);
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer(cause.getMessage().getBytes(StandardCharsets.UTF_8))));
        }
    }
}

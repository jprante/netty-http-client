package org.xbib.netty.http.server.test;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.xbib.netty.http.client.Client;
import org.xbib.netty.http.client.Request;
import org.xbib.netty.http.common.HttpAddress;
import org.xbib.netty.http.server.Domain;
import org.xbib.netty.http.server.Server;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(NettyHttpExtension.class)
class StreamTest {

    @Test
    void testServerStreams() throws Exception {
        HttpAddress httpAddress = HttpAddress.http1("localhost", 8008);
        Domain domain = Domain.builder(httpAddress)
                .singleEndpoint("/", (request, response) -> {
                    ByteBufInputStream inputStream = request.getInputStream();
                    String content = inputStream.readLine();
                    assertEquals("my body parameter", content);
                    ByteBufOutputStream outputStream = response.getOutputStream();
                    outputStream.writeBytes("Hello World");
                    response.withStatus(HttpResponseStatus.OK)
                            .withContentType("text/plain")
                            .write(outputStream);
                })
                .build();
        Server server = Server.builder(domain)
                .build();
        Client client = Client.builder()
                .build();
        int max = 1;
        final AtomicInteger count = new AtomicInteger(0);
        try {
            server.accept();
            Request request = Request.get().setVersion(HttpVersion.HTTP_1_1)
                    .url(server.getServerConfig().getAddress().base().resolve("/"))
                    .content("my body parameter", "text/plain")
                    .build()
                    .setResponseListener(resp -> {
                        if (resp.getStatus().getCode() == HttpResponseStatus.OK.code()) {
                            assertEquals("Hello World", resp.getBodyAsString(StandardCharsets.UTF_8));
                            count.incrementAndGet();
                        }
                    });
            for (int i = 0; i < max; i++) {
                client.execute(request).get();
            }
        } finally {
            server.shutdownGracefully();
            client.shutdownGracefully();
        }
        assertEquals(max, count.get());
    }
}
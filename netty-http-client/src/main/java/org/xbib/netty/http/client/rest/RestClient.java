package org.xbib.netty.http.client.rest;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.xbib.net.URL;
import org.xbib.netty.http.client.Client;
import org.xbib.netty.http.common.HttpAddress;
import org.xbib.netty.http.client.Request;
import org.xbib.netty.http.client.RequestBuilder;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class RestClient {

    private static final Client client = new Client();

    private HttpResponse response;

    private RestClient() {
    }

    public void setResponse(HttpResponse response) {
        this.response = response;
    }

    public HttpResponse getResponse() {
        return response;
    }

    public String asString() {
        return asString(StandardCharsets.UTF_8);
    }

    public String asString(Charset charset) {
        ChannelBuffer byteBuf = response != null ? response.getContent() : null;
        return byteBuf != null && byteBuf.readable() ? response.getContent().toString(charset) : null;
    }

    public void close() throws IOException {
        client.shutdownGracefully();
    }

    public static RestClient get(String urlString) throws IOException {
        return method(urlString, null, null, HttpMethod.GET);
    }

    public static RestClient delete(String urlString) throws IOException {
        return method(urlString, null, null, HttpMethod.DELETE);
    }

    public static RestClient post(String urlString, String body) throws IOException {
        return method(urlString, body, StandardCharsets.UTF_8, HttpMethod.POST);
    }

    public static RestClient post(String urlString, ChannelBuffer content) throws IOException {
        return method(urlString, content, HttpMethod.POST);
    }

    public static RestClient put(String urlString, String body) throws IOException {
        return method(urlString, body, StandardCharsets.UTF_8, HttpMethod.PUT);
    }

    public static RestClient put(String urlString, ChannelBuffer content) throws IOException {
        return method(urlString, content, HttpMethod.PUT);
    }

    public static RestClient method(String urlString,
                                    String body, Charset charset,
                                    HttpMethod httpMethod) throws IOException {
        ChannelBuffer byteBuf = null;
        if (body != null && charset != null) {
            byteBuf = ChannelBuffers.copiedBuffer(body, charset);
        }
        return method(urlString, byteBuf, httpMethod);
    }

    public static RestClient method(String urlString,
                                    ChannelBuffer byteBuf,
                                    HttpMethod httpMethod) throws IOException {
        URL url = URL.create(urlString);
        RestClient restClient = new RestClient();
        RequestBuilder requestBuilder = Request.builder(httpMethod).url(url);
        if (byteBuf != null) {
            requestBuilder.content(byteBuf);
        }
        client.newTransport(HttpAddress.http1(url))
                .execute(requestBuilder.build().setResponseListener(restClient::setResponse)).get();
        return restClient;
    }
}

package org.xbib.netty.http.client.listener;

import org.jboss.netty.handler.codec.http.HttpResponse;

@FunctionalInterface
public interface ResponseListener {

    void onResponse(HttpResponse httpResponse);
}

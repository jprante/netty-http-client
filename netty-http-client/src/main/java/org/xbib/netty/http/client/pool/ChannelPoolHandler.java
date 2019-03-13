package org.xbib.netty.http.client.pool;

import org.jboss.netty.channel.Channel;

public interface ChannelPoolHandler {

    void channelReleased(Channel ch) throws Exception;

    void channelCreated(Channel ch) throws Exception;

}

package com.orlando.resource;

import com.orlando.service.SimpleChatService;

import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/chat")
@Produces(MediaType.TEXT_PLAIN)
public class ChatResource {
    private static final Logger LOG = Logger.getLogger(ChatResource.class);

    @Inject
    SimpleChatService chat;

    @GET
    public String chat(@QueryParam("q") String q) {
        if (q == null || q.isBlank())
            return "missing q";
        LOG.infov("输入的问题: {0}", q);
        String result = chat.chat(q);
        LOG.infov("返回的结果: {0}", result);
        return result;
    }
}

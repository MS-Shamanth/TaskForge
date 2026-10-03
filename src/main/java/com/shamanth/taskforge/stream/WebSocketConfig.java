package com.shamanth.taskforge.stream;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws");
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic");
		registry.setPreservePublishOrder(true);
	}

	/*
	 * The feed is server-to-client only. By default the simple broker relays every client message of type MESSAGE to
	 * the subscribers of its destination, so any client could push a forged event to every subscriber of /topic/jobs.
	 * A client can produce that type with a SEND frame, and also with a MESSAGE frame, which normally only the server
	 * sends. So this interceptor checks the message type, not the STOMP command. Spring answers a rejected frame with
	 * a STOMP ERROR frame and closes that client's connection. Server-side publishing (SimpMessagingTemplate) goes
	 * through the broker channel and is not affected.
	 */
	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(new ChannelInterceptor() {

			@Override
			public Message<?> preSend(Message<?> message, MessageChannel channel) {
				if (SimpMessageType.MESSAGE.equals(SimpMessageHeaderAccessor.getMessageType(message.getHeaders()))) {
					throw new MessageDeliveryException(message,
							"Clients cannot publish messages; the TaskForge feed is server-to-client only");
				}
				return message;
			}

		});
	}

}

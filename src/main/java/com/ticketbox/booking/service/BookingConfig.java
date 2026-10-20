package com.ticketbox.booking.service;

import com.ticketbox.booking.ticket.TicketProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BookingProperties.class, TicketProperties.class})
public class BookingConfig {
}

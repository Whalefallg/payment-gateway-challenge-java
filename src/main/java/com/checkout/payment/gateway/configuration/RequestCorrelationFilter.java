package com.checkout.payment.gateway.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-ID";
  private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    long startNanos = System.nanoTime();
    MDC.put("request_id", requestId);
    response.setHeader(HEADER, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      LOG.info("Request completed method={} path={} status={} duration_ms={}",
          request.getMethod(), request.getRequestURI(), response.getStatus(),
          (System.nanoTime() - startNanos) / 1_000_000);
      MDC.remove("request_id");
    }
  }
}

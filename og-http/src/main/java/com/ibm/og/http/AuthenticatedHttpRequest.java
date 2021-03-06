/* Copyright (c) IBM Corporation 2016. All Rights Reserved.
 * Project name: Object Generator
 * This project is licensed under the Apache License 2.0, see LICENSE.
 */

package com.ibm.og.http;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import com.ibm.og.util.io.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.og.api.AuthenticatedRequest;
import com.ibm.og.api.Body;
import com.ibm.og.api.DataType;
import com.ibm.og.api.Method;
import com.ibm.og.api.Operation;
import com.ibm.og.api.Request;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.net.HttpHeaders;

/**
 * A defacto implementation of the {@code AuthenticatedRequest} interface
 * 
 * @since 1.0
 */
public class AuthenticatedHttpRequest implements AuthenticatedRequest {
  private static Logger _logger = LoggerFactory.getLogger(AuthenticatedHttpRequest.class);
  private final Request request;
  private final Map<String, List<String>> queryParameters;
  private final Map<String, String> requestHeaders;
  private InputStream content;

  /**
   * Constructs an authenticated request object which wraps and underlying request
   * 
   * @param request the base request to wrap
   */
  public AuthenticatedHttpRequest(final Request request) {
    this.request = checkNotNull(request);
    this.queryParameters = Maps.newHashMap();
    for (final Map.Entry<String, List<String>> entry : request.getQueryParameters().entrySet()) {
      this.queryParameters.put(entry.getKey(), Lists.newArrayList(entry.getValue()));
    }
    this.requestHeaders = Maps.newHashMap(request.headers());

    this.content = Streams.create(request.getBody());
    this.content.mark(Integer.MAX_VALUE);
    if (request.getBody().getDataType() != DataType.NONE) {
      this.setContentLength(request.getBody().getSize());
    }
  }

  @Override
  public Method getMethod() {
    return this.request.getMethod();
  }

  @Override
  public URI getUri() {
    return this.request.getUri();
  }

  @Override
  public Map<String, List<String>> getQueryParameters() {
    return this.queryParameters;
  }

  /**
   * Adds a query parameter to this request
   * 
   * @param key query parameter key
   * @param value optional query parameter value
   */
  public void addQueryParameter(final String key, final String value) {
    List<String> parameterValues = this.queryParameters.get(checkNotNull(key));
    if (parameterValues == null) {
      parameterValues = Lists.newArrayList();
      this.queryParameters.put(key, parameterValues);
    }
    parameterValues.add(value);
  }

  @Override
  public Map<String, String> headers() {
    return this.requestHeaders;
  }

  /**
   * Adds a header to this request
   * 
   * @param key header key
   * @param value header value
   */
  public void addHeader(final String key, final String value) {
    this.requestHeaders.put(checkNotNull(key), checkNotNull(value));
  }

  @Override
  public Body getBody() {
    return this.request.getBody();
  }

  @Override
  public Map<String, String> getContext() {
    return this.request.getContext();
  }

  @Override
  public InputStream getContent() {
    return this.content;
  }

  /**
   * Sets the content stream for this request. Often during the course of authenticating a request,
   * the original content stream needs to be wrapped, via a combination of {@code getContent} and
   * this method.
   * 
   * @param content the new content stream for this request
   */
  public void setContent(final InputStream content) {
    this.content = content;
  }

  @Override
  public long getContentLength() {
    if (this.headers().containsKey(HttpHeaders.CONTENT_LENGTH)) {
      return Long.parseLong(this.headers().get(HttpHeaders.CONTENT_LENGTH));
    }
    return 0;
  }

  /**
   * Sets the content length for the content stream of this request
   * 
   * @param length content stream length
   */
  public void setContentLength(final long length) {
    checkArgument(length >= 0, "length must be >= 0 [%s]", length);
    this.addHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(length));
  }

  @Override
  public String toString() {
    return String.format(
        "AuthenticatedHttpRequest [%n" + "method=%s,%n" + "uri=%s,%n" + "queryParameters=%s,%n"
            + "headers=%s%n" + "body=%s%n" + "context=%s%n" + "content=%s%n"
            + "contentLength=%s%n]",
        this.getMethod(), this.getUri(), this.queryParameters, this.requestHeaders, this.getBody(),
        this.getContext(), this.content, this.getContentLength());
  }

  @Override
  public Operation getOperation() {
    return this.request.getOperation();
  }
}

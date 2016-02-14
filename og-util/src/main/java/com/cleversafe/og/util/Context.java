/*
 * Copyright (C) 2005-2015 Cleversafe, Inc. All rights reserved.
 * 
 * Contact Information: Cleversafe, Inc. 222 South Riverside Plaza Suite 1700 Chicago, IL 60606, USA
 * 
 * licensing@cleversafe.com
 */

package com.cleversafe.og.util;

/**
 * Custom Object Generator Context keys
 * 
 * @since 1.0
 */
public class Context {
  public static final String X_OG_REQUEST_ID = "x-og-request-id";
  public static final String X_OG_OBJECT_NAME = "x-og-object-name";
  // FIXME header to differentiate between objects which can be stored in the object manager
  public static final String X_OG_SEQUENTIAL_OBJECT_NAME = "x-og-sequential-object-name";
  public static final String X_OG_OBJECT_SIZE = "x-og-object-size";
  public static final String X_OG_CONTAINER_SUFFIX = "x-og-container-suffix";
  public static final String X_OG_USERNAME = "x-og-username";
  public static final String X_OG_PASSWORD = "x-og-password";
  // FIXME refactor this into separate openstack guice module
  public static final String X_OG_KEYSTONE_TOKEN = "x-og-keystone-token";
  public static final String X_OG_RESPONSE_BODY_CONSUMER = "x-og-response-body-consumer";

  private Context() {}
}
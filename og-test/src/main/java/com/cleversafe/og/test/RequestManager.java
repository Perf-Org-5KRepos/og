/*
 * Copyright (C) 2005-2015 Cleversafe, Inc. All rights reserved.
 * 
 * Contact Information: Cleversafe, Inc. 222 South Riverside Plaza Suite 1700 Chicago, IL 60606, USA
 * 
 * licensing@cleversafe.com
 */

package com.cleversafe.og.test;

import com.cleversafe.og.api.Request;
import com.google.common.base.Supplier;

/**
 * A manager and supplier of requests. Simple implementations may provide no additional
 * functionality over a basic {@code Supplier&lt;Request&gt;}, while more sophisticated
 * implementations may vary requests over time, received responses, etc.
 * 
 * @since 1.0
 */
public interface RequestManager extends Supplier<Request> {
}

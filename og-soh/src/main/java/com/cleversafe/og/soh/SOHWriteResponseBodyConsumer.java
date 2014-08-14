//
// Copyright (C) 2005-2011 Cleversafe, Inc. All rights reserved.
//
// Contact Information:
// Cleversafe, Inc.
// 222 South Riverside Plaza
// Suite 1700
// Chicago, IL 60606, USA
//
// licensing@cleversafe.com
//
// END-OF-HEADER
//
// -----------------------
// @author: rveitch
//
// Date: Jul 15, 2014
// ---------------------

package com.cleversafe.og.soh;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.cleversafe.og.http.Headers;
import com.cleversafe.og.http.ResponseBodyConsumer;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;

/**
 * A response body consumer which processes and returns an SOH object name from the response body of
 * an SOH PUT response
 * 
 * @since 1.0
 */
public class SOHWriteResponseBodyConsumer implements ResponseBodyConsumer
{
   @Override
   public Iterator<Entry<String, String>> consume(final int statusCode, final InputStream response)
         throws IOException
   {
      if (statusCode != 201)
      {
         final Set<Entry<String, String>> emptySet = ImmutableSet.of();
         return emptySet.iterator();
      }
      checkNotNull(response);

      final BufferedReader reader =
            new BufferedReader(new InputStreamReader(response, Charsets.UTF_8));

      final Map<String, String> metadata = new HashMap<String, String>(1);
      metadata.put(Headers.OBJECT_NAME.toString(), reader.readLine());
      while ((reader.readLine()) != null)
      {
         // consume the stream, for SOH writes there should never be anything else
      }
      return metadata.entrySet().iterator();
   }
}

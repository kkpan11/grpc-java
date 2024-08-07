/*
 * Copyright 2016 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkArgument;
import static io.grpc.netty.Utils.TE_HEADER;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;
import static io.netty.util.AsciiString.isUpperCase;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.io.BaseEncoding;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.Metadata;
import io.netty.handler.codec.CharSequenceValueConverter;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import io.netty.util.internal.PlatformDependent;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A headers utils providing custom gRPC implementations of {@link DefaultHttp2HeadersDecoder}.
 */
class GrpcHttp2HeadersUtils {
  static final class GrpcHttp2ServerHeadersDecoder extends DefaultHttp2HeadersDecoder {

    GrpcHttp2ServerHeadersDecoder(long maxHeaderListSize) {
      super(true, maxHeaderListSize);
    }

    @Override
    protected GrpcHttp2InboundHeaders newHeaders() {
      return new GrpcHttp2RequestHeaders(numberOfHeadersGuess());
    }
  }

  static final class GrpcHttp2ClientHeadersDecoder extends DefaultHttp2HeadersDecoder {

    GrpcHttp2ClientHeadersDecoder(long maxHeaderListSize) {
      super(true, maxHeaderListSize);
    }

    @Override
    protected GrpcHttp2InboundHeaders newHeaders() {
      return new GrpcHttp2ResponseHeaders(numberOfHeadersGuess());
    }
  }

  /**
   * A {@link Http2Headers} implementation optimized for inbound/received headers.
   *
   * <p>Header names and values are stored in simple arrays, which makes insert run in O(1)
   * and retrievial a O(n). Header name equality is not determined by the equals implementation of
   * {@link CharSequence} type, but by comparing two names byte to byte.
   *
   * <p>All {@link CharSequence} input parameters and return values are required to be of type
   * {@link AsciiString}.
   */
  abstract static class GrpcHttp2InboundHeaders extends AbstractHttp2Headers {

    private static final AsciiString binaryHeaderSuffix =
        new AsciiString(Metadata.BINARY_HEADER_SUFFIX.getBytes(US_ASCII));

    private byte[][] namesAndValues;
    private AsciiString[] values;
    private int namesAndValuesIdx;

    GrpcHttp2InboundHeaders(int numHeadersGuess) {
      checkArgument(numHeadersGuess > 0, "numHeadersGuess needs to be positive: %s",
          numHeadersGuess);
      namesAndValues = new byte[numHeadersGuess * 2][];
      values = new AsciiString[numHeadersGuess];
    }

    protected Http2Headers add(AsciiString name, AsciiString value) {
      byte[] nameBytes = bytes(name);
      byte[] valueBytes;
      if (!name.endsWith(binaryHeaderSuffix)) {
        valueBytes = bytes(value);
        addHeader(value, nameBytes, valueBytes);
        return this;
      }
      int startPos = 0;
      int endPos = -1;
      while (endPos < value.length()) {
        int indexOfComma = value.indexOf(',', startPos);
        endPos = indexOfComma == AsciiString.INDEX_NOT_FOUND ? value.length() : indexOfComma;
        AsciiString curVal = value.subSequence(startPos, endPos, false);
        valueBytes = BaseEncoding.base64().decode(curVal);
        startPos = indexOfComma + 1;
        addHeader(curVal, nameBytes, valueBytes);
      }
      return this;
    }

    private void addHeader(AsciiString value, byte[] nameBytes, byte[] valueBytes) {
      if (namesAndValuesIdx == namesAndValues.length) {
        expandHeadersAndValues();
      }
      values[namesAndValuesIdx / 2] = value;
      namesAndValues[namesAndValuesIdx] = nameBytes;
      namesAndValuesIdx++;
      namesAndValues[namesAndValuesIdx] = valueBytes;
      namesAndValuesIdx++;
    }

    protected CharSequence get(AsciiString name) {
      for (int i = 0; i < namesAndValuesIdx; i += 2) {
        if (equals(name, namesAndValues[i])) {
          return values[i / 2];
        }
      }
      return null;
    }

    @Override
    public boolean contains(CharSequence name) {
      return get(name) != null;
    }

    @Override
    public CharSequence status() {
      return get(Http2Headers.PseudoHeaderName.STATUS.value());
    }

    @Override
    public List<CharSequence> getAll(CharSequence csName) {
      AsciiString name = requireAsciiString(csName);
      List<CharSequence> returnValues = new ArrayList<>(4);
      for (int i = 0; i < namesAndValuesIdx; i += 2) {
        if (equals(name, namesAndValues[i])) {
          returnValues.add(values[i / 2]);
        }
      }
      return returnValues;
    }

    @CanIgnoreReturnValue
    @Override
    public boolean remove(CharSequence csName) {
      AsciiString name = requireAsciiString(csName);
      int i = 0;
      for (; i < namesAndValuesIdx; i += 2) {
        if (equals(name, namesAndValues[i])) {
          break;
        }
      }
      if (i >= namesAndValuesIdx) {
        return false;
      }
      int dest = i;
      for (; i < namesAndValuesIdx; i += 2) {
        if (equals(name, namesAndValues[i])) {
          continue;
        }
        values[dest / 2] = values[i / 2];
        namesAndValues[dest] = namesAndValues[i];
        namesAndValues[dest + 1] = namesAndValues[i + 1];
        dest += 2;
      }
      namesAndValuesIdx = dest;
      return true;
    }

    @Override
    public Http2Headers set(CharSequence name, CharSequence value) {
      remove(name);
      return add(name, value);
    }

    @Override
    public Http2Headers setLong(CharSequence name, long value) {
      return set(name, AsciiString.of(CharSequenceValueConverter.INSTANCE.convertLong(value)));
    }

    /**
     * Returns the header names and values as bytes. An even numbered index contains the
     * {@code byte[]} representation of a header name (in insertion order), and the subsequent
     * odd index number contains the corresponding header value.
     *
     * <p>The values of binary headers (with a -bin suffix), are already base64 decoded.
     *
     * <p>The array may contain several {@code null} values at the end. A {@code null} value an
     * index means that all higher numbered indices also contain {@code null} values.
     */
    byte[][] namesAndValues() {
      return namesAndValues;
    }

    /**
     * Returns the number of none-null headers in {@link #namesAndValues()}.
     */
    protected int numHeaders() {
      return namesAndValuesIdx / 2;
    }

    protected static boolean equals(AsciiString str0, byte[] str1) {
      return equals(str0.array(), str0.arrayOffset(), str0.length(), str1, 0, str1.length);
    }

    protected static boolean equals(AsciiString str0, AsciiString str1) {
      return equals(str0.array(), str0.arrayOffset(), str0.length(), str1.array(),
          str1.arrayOffset(), str1.length());
    }

    protected static boolean equals(byte[] bytes0, int offset0, int length0, byte[] bytes1,
        int offset1, int length1) {
      if (length0 != length1) {
        return false;
      }
      return PlatformDependent.equals(bytes0, offset0, bytes1, offset1, length0);
    }

    protected static byte[] bytes(AsciiString str) {
      return str.isEntireArrayUsed() ? str.array() : str.toByteArray();
    }

    protected static AsciiString requireAsciiString(CharSequence cs) {
      if (!(cs instanceof AsciiString)) {
        throw new IllegalArgumentException("AsciiString expected. Was: " + cs.getClass().getName());
      }
      return (AsciiString) cs;
    }

    protected static boolean isPseudoHeader(AsciiString str) {
      return !str.isEmpty() && str.charAt(0) == ':';
    }

    protected AsciiString validateName(AsciiString str) {
      int offset = str.arrayOffset();
      int length = str.length();
      final byte[] data = str.array();
      for (int i = offset; i < offset + length; i++) {
        if (isUpperCase(data[i])) {
          PlatformDependent.throwException(connectionError(PROTOCOL_ERROR,
              "invalid header name '%s'", str));
        }
      }
      return str;
    }

    private void expandHeadersAndValues() {
      int newValuesLen = Math.max(2, values.length + values.length / 2);
      int newNamesAndValuesLen = newValuesLen * 2;

      byte[][] newNamesAndValues = new byte[newNamesAndValuesLen][];
      AsciiString[] newValues = new AsciiString[newValuesLen];
      System.arraycopy(namesAndValues, 0, newNamesAndValues, 0, namesAndValues.length);
      System.arraycopy(values, 0, newValues, 0, values.length);
      namesAndValues = newNamesAndValues;
      values = newValues;
    }

    @Override
    public int size() {
      return numHeaders();
    }

    @Override
    public Iterator<Map.Entry<CharSequence, CharSequence>> iterator() {
      return namesAndValuesToImmutableList().iterator();
    }

    protected static void appendNameAndValue(StringBuilder builder, CharSequence name,
        CharSequence value, boolean prependSeparator) {
      if (prependSeparator) {
        builder.append(", ");
      }
      builder.append(name).append(": ").append(value);
    }

    private List<Map.Entry<CharSequence, CharSequence>> namesAndValuesToImmutableList() {
      ArrayList<Map.Entry<CharSequence, CharSequence>> list = new ArrayList<>(values.length);
      for (int i = 0; i < namesAndValuesIdx; i += 2) {
        String name = new String(namesAndValues[i], US_ASCII);
        // If binary headers, the value is base64 encoded.
        AsciiString value = values[i / 2];
        list.add(new AbstractMap.SimpleImmutableEntry<CharSequence, CharSequence>(name, value));
      }
      return Collections.unmodifiableList(list);
    }

    protected final String namesAndValuesToString() {
      StringBuilder builder = new StringBuilder();
      boolean prependSeparator = false;
      for (Map.Entry<CharSequence, CharSequence> entry : namesAndValuesToImmutableList()) {
        appendNameAndValue(builder, entry.getKey(), entry.getValue(), prependSeparator);
        prependSeparator = true;
      }
      return builder.toString();
    }
  }

  /**
   * A {@link GrpcHttp2InboundHeaders} implementation, optimized for HTTP/2 request headers. That
   * is, HTTP/2 request pseudo headers are stored in dedicated fields and are NOT part of the
   * array returned by {@link #namesAndValues()}.
   *
   * <p>This class only implements the methods used by {@link NettyServerHandler} and tests. All
   * other methods throw an {@link UnsupportedOperationException}.
   */
  static final class GrpcHttp2RequestHeaders extends GrpcHttp2InboundHeaders {

    private static final AsciiString PATH_HEADER = AsciiString.of(":path");
    private static final AsciiString AUTHORITY_HEADER = AsciiString.of(":authority");
    private static final AsciiString METHOD_HEADER = AsciiString.of(":method");
    private static final AsciiString SCHEME_HEADER = AsciiString.of(":scheme");

    private AsciiString path;
    private AsciiString authority;
    private AsciiString method;
    private AsciiString scheme;
    private AsciiString te;

    GrpcHttp2RequestHeaders(int numHeadersGuess) {
      super(numHeadersGuess);
    }

    @Override
    public Http2Headers add(CharSequence csName, CharSequence csValue) {
      AsciiString name = validateName(requireAsciiString(csName));
      AsciiString value = requireAsciiString(csValue);
      if (isPseudoHeader(name)) {
        AsciiString previous = getPseudoHeader(name);
        if (previous != null) {
          PlatformDependent.throwException(
              connectionError(PROTOCOL_ERROR, "Duplicate %s header", name));
        }
        setPseudoHeader(name, value);
        return this;
      }
      if (equals(TE_HEADER, name)) {
        te = value;
        return this;
      }
      return add(name, value);
    }

    @Override
    public CharSequence get(CharSequence csName) {
      AsciiString name = requireAsciiString(csName);
      if (isPseudoHeader(name)) {
        return getPseudoHeader(name);
      }
      if (equals(TE_HEADER, name)) {
        return te;
      }
      return get(name);
    }

    private AsciiString getPseudoHeader(AsciiString name) {
      if (equals(PATH_HEADER, name)) {
        return path;
      } else if (equals(AUTHORITY_HEADER, name)) {
        return authority;
      } else if (equals(METHOD_HEADER, name)) {
        return method;
      } else if (equals(SCHEME_HEADER, name)) {
        return scheme;
      } else {
        return null;
      }
    }

    private void setPseudoHeader(AsciiString name, AsciiString value) {
      if (equals(PATH_HEADER, name)) {
        path = value;
      } else if (equals(AUTHORITY_HEADER, name)) {
        authority = value;
      } else if (equals(METHOD_HEADER, name)) {
        method = value;
      } else if (equals(SCHEME_HEADER, name)) {
        scheme = value;
      } else {
        PlatformDependent.throwException(
            connectionError(PROTOCOL_ERROR, "Illegal pseudo-header '%s' in request.", name));
        throw new AssertionError(); // Make flow control obvious to javac
      }
    }

    @Override
    public CharSequence path() {
      return path;
    }

    @Override
    public CharSequence authority() {
      return authority;
    }

    @Override
    public CharSequence method() {
      return method;
    }

    @Override
    public CharSequence scheme() {
      return scheme;
    }

    @Override
    public List<CharSequence> getAll(CharSequence csName) {
      AsciiString name = requireAsciiString(csName);
      if (isPseudoHeader(name)) {
        AsciiString value = getPseudoHeader(name);
        if (value == null) {
          return Collections.emptyList();
        } else {
          return Collections.singletonList(value);
        }
      }
      if (equals(TE_HEADER, name)) {
        return Collections.singletonList((CharSequence) te);
      }
      return super.getAll(csName);
    }

    @Override
    public boolean remove(CharSequence csName) {
      AsciiString name = requireAsciiString(csName);
      if (isPseudoHeader(name)) {
        if (getPseudoHeader(name) == null) {
          return false;
        } else {
          setPseudoHeader(name, null);
          return true;
        }
      }
      if (equals(TE_HEADER, name)) {
        boolean wasPresent = te != null;
        te = null;
        return wasPresent;
      }
      return super.remove(name);
    }

    /**
     * This method is called in tests only.
     */
    @Override
    public int size() {
      int size = 0;
      if (path != null) {
        size++;
      }
      if (authority != null) {
        size++;
      }
      if (method != null) {
        size++;
      }
      if (scheme != null) {
        size++;
      }
      if (te != null) {
        size++;
      }
      size += super.size();
      return size;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder(getClass().getSimpleName()).append('[');
      boolean prependSeparator = false;

      if (path != null) {
        appendNameAndValue(builder, PATH_HEADER, path, prependSeparator);
        prependSeparator = true;
      }
      if (authority != null) {
        appendNameAndValue(builder, AUTHORITY_HEADER, authority, prependSeparator);
        prependSeparator = true;
      }
      if (method != null) {
        appendNameAndValue(builder, METHOD_HEADER, method, prependSeparator);
        prependSeparator = true;
      }
      if (scheme != null) {
        appendNameAndValue(builder, SCHEME_HEADER, scheme, prependSeparator);
        prependSeparator = true;
      }
      if (te != null) {
        appendNameAndValue(builder, TE_HEADER, te, prependSeparator);
      }

      String namesAndValues = namesAndValuesToString();

      if (builder.length() > 0 && namesAndValues.length() > 0) {
        builder.append(", ");
      }

      builder.append(namesAndValues);
      builder.append(']');

      return builder.toString();
    }
  }

  /**
   * This class only implements the methods used by {@link NettyClientHandler} and tests. All
   * other methods throw an {@link UnsupportedOperationException}.
   *
   * <p>Unlike in {@link GrpcHttp2ResponseHeaders} the {@code :status} pseudo-header is not treated
   * special and is part of {@link #namesAndValues}.
   */
  static final class GrpcHttp2ResponseHeaders extends GrpcHttp2InboundHeaders {

    GrpcHttp2ResponseHeaders(int numHeadersGuess) {
      super(numHeadersGuess);
    }

    @Override
    public Http2Headers add(CharSequence csName, CharSequence csValue) {
      AsciiString name = validateName(requireAsciiString(csName));
      AsciiString value = requireAsciiString(csValue);
      return add(name, value);
    }

    @Override
    public CharSequence get(CharSequence csName) {
      AsciiString name = requireAsciiString(csName);
      return get(name);
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder(getClass().getSimpleName()).append('[');
      builder.append(namesAndValuesToString()).append(']');
      return builder.toString();
    }
  }
}

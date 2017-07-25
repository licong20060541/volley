/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley.toolbox;

import com.android.volley.Cache;
import com.android.volley.NetworkResponse;

import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.protocol.HTTP;

import java.util.Map;

/**
 * Utility methods for parsing HTTP headers.
 */
public class HttpHeaderParser {

    /**
     * Extracts a {@link com.android.volley.Cache.Entry} from a {@link NetworkResponse}.
     *
     * @param response The network response to parse headers from
     * @return a cache entry for the given response, or null if the response is not cacheable.
     * 返回的是缓存，如果不允许则null
     *
     *
     * Cache-Control指定了请求和响应遵循的缓存机制。好的缓存机制可以减少对网络带宽的占用，
     * 可以提高访问速度，提高用户的体验，还可以减轻服务器的负担。
     * Cache-Control主要有以下几种类型：
     * (1) 请求Request：
     * [1] no-cache  ---- 不要读取缓存中的文件，要求向WEB服务器重新请求
     * [2] no-store    ---- 请求和响应都禁止被缓存
     * [2] max-age： ---- 表示当访问此网页后的max-age秒内再次访问不会去服务器请求，其功能与Expires类似，
     *          只是Expires是根据某个特定日期值做比较。一但缓存者自身的时间不准确.则结果可能就是错误的，
     *          而max-age,显然无此问题.。Max-age的优先级也是高于Expires的。
     * [3] max-stale  ---- 允许读取过期时间必须小于max-stale 值的缓存对象。
     * [4] min-fresh ---- 接受其max-age生命期大于其-当前时间 跟 min-fresh 值之和-的缓存对象;
     *              指示客户机可以接收响应时间小于当前时间加上指定时间的响应
     * [5] only-if-cached ---- 告知缓存者,我希望内容来自缓存，我并不关心被缓存响应,是否是新鲜的.
     * [6] no-transform   ---- 告知代理,不要更改媒体类型,比如jpg,被你改成png.
     *
     * (2) 响应Response：
     * [1] public    ---- 数据内容皆被储存起来，就连有密码保护的网页也储存，安全性很低
     * [2] private    ---- 数据内容只能被储存到私有的cache，仅对某个用户有效，不能共享
     * [3] no-cache    ---- 可以缓存，但是只有在跟WEB服务器验证了其有效后，才能返回给客户端
     * [4] no-store  ---- 请求和响应都禁止被缓存
     * [4] max-age：   ----- 本响应包含的对象的过期时间
     * [5] Must-revalidate ---- 如果缓存过期了，会再次和原来的服务器确定是否为最新数据，而不是和中间的proxy
     * [6] max-stale  ----  允许读取过期时间必须小于max-stale 值的缓存对象。
     * [7] proxy-revalidate  ---- 与Must-revalidate类似，区别在于：proxy-revalidate要排除掉用户代理的
     *             缓存的。即其规则并不应用于用户代理的本地缓存上。
     * [8] s-maxage  ---- 与max-age的唯一区别是,s-maxage仅仅应用于共享缓存.而不应用于用户代理的本地缓存
     *             等针对单用户的缓存. 另外,s-maxage的优先级要高于max-age.
     * [9] no-transform   ---- 告知代理,不要更改媒体类型,比如jpg,被你改成png.
     * [10] stale-while-revalidate   -----
     *   用于指定一个默认秒数（间隔是秒因为回复TTL精度是1秒），在期间缓存还在后台进行重新校验时可以立刻返回
     *   一个陈旧的回复（默认是2）；该设置会被stale-while-revalidate HTTP Cache-Control扩展重写（RFC 5861）
     *
     */
    public static Cache.Entry parseCacheHeaders(NetworkResponse response) {
        long now = System.currentTimeMillis();

        Map<String, String> headers = response.headers;

        long serverDate = 0;
        long lastModified = 0;
        long serverExpires = 0; // 存储服务器时间的临时变量
        long softExpire = 0; // 本地缓存使用
        long finalExpire = 0; // 本地缓存使用
        long maxAge = 0;
        long staleWhileRevalidate = 0;//正在重新认证时的不新鲜的时间
        boolean hasCacheControl = false;
        boolean mustRevalidate = false;//重新认证

        String serverEtag = null;
        String headerValue;

        headerValue = headers.get("Date");
        if (headerValue != null) {
            serverDate = parseDateAsEpoch(headerValue);
        }

        headerValue = headers.get("Cache-Control");
        if (headerValue != null) {
            hasCacheControl = true;
            String[] tokens = headerValue.split(",");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i].trim();
                if (token.equals("no-cache") || token.equals("no-store")) {
                    return null; // 不要cache
                } else if (token.startsWith("max-age=")) {
                    try {
                        maxAge = Long.parseLong(token.substring(8));
                    } catch (Exception e) {
                    }
                } else if (token.startsWith("stale-while-revalidate=")) {
                    try {
                        staleWhileRevalidate = Long.parseLong(token.substring(23));
                    } catch (Exception e) {
                    }
                } else if (token.equals("must-revalidate") || token.equals("proxy-revalidate")) {
                    mustRevalidate = true;
                }
            }
        }

        headerValue = headers.get("Expires");
        if (headerValue != null) {
            serverExpires = parseDateAsEpoch(headerValue);
        }

        headerValue = headers.get("Last-Modified");
        if (headerValue != null) {
            lastModified = parseDateAsEpoch(headerValue);
        }

        serverEtag = headers.get("ETag");

        // Cache-Control takes precedence over an Expires header, even if both exist and Expires
        // is more restrictive.
        if (hasCacheControl) {
            softExpire = now + maxAge * 1000; // 转换成本地时间
            // mustRevalidate
            finalExpire = mustRevalidate
                    ? softExpire
                    : softExpire + staleWhileRevalidate * 1000; // 加上重新认证的时间
        } else if (serverDate > 0 && serverExpires >= serverDate) {
            // Default semantic for Expire header in HTTP specification is softExpire.
            softExpire = now + (serverExpires - serverDate); // 转换成本地时间
            finalExpire = softExpire;
        }

        Cache.Entry entry = new Cache.Entry();
        entry.data = response.data;
        entry.etag = serverEtag;
        entry.softTtl = softExpire; // 只有这个超时了，则设置response.intermediate = true;
        entry.ttl = finalExpire; // 这个超时了则必须重新请求，不能使用缓存
        entry.serverDate = serverDate;
        entry.lastModified = lastModified;
        entry.responseHeaders = headers;

        return entry;
    }

    /**
     * Parse date in RFC1123 format, and return its value as epoch
     */
    public static long parseDateAsEpoch(String dateStr) {
        try {
            // Parse date in RFC1123 format if this header contains one
            return DateUtils.parseDate(dateStr).getTime();
        } catch (DateParseException e) {
            // Date in invalid format, fallback to 0
            return 0;
        }
    }

    /**
     * Retrieve a charset from headers
     * 基本协议规范
     *
     * @param headers An {@link java.util.Map} of headers
     * @param defaultCharset Charset to return if none can be found
     * @return Returns the charset specified in the Content-Type of this header,
     * or the defaultCharset if none can be found.
     */
    public static String parseCharset(Map<String, String> headers, String defaultCharset) {
        String contentType = headers.get(HTTP.CONTENT_TYPE);
        if (contentType != null) {
            String[] params = contentType.split(";");
            for (int i = 1; i < params.length; i++) {
                String[] pair = params[i].trim().split("=");
                if (pair.length == 2) {
                    if (pair[0].equals("charset")) {
                        return pair[1];
                    }
                }
            }
        }

        return defaultCharset;
    }

    /**
     * Returns the charset specified in the Content-Type of this header,
     * or the HTTP default (ISO-8859-1) if none can be found.
     */
    public static String parseCharset(Map<String, String> headers) {
        return parseCharset(headers, HTTP.DEFAULT_CONTENT_CHARSET);
    }
}

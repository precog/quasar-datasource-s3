/*
 * Copyright 2014–2019 SlamData Inc.
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

package quasar.physical.s3

import slamdata.Predef._

import java.net.{Proxy, InetSocketAddress}
import java.net.Proxy.{Type => ProxyType}

import org.specs2.mutable.Specification

object AsyncHttpClientBuilderSpec extends Specification {
  "direct proxies sort last" >> {
    val HttpProxy = new Proxy(ProxyType.HTTP, new InetSocketAddress("foo.com", 1337))
    val NoProxy = Proxy.NO_PROXY
    val SocksProxy = new Proxy(ProxyType.SOCKS, new InetSocketAddress("bar.com", 1337))

    AsyncHttpClientBuilder.sortProxies(
      List(NoProxy, HttpProxy, SocksProxy)) must_== List(HttpProxy, SocksProxy, NoProxy)
  }
}

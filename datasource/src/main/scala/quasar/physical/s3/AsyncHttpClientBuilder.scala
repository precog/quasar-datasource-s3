/*
 * Copyright 2020 Precog Data
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

import quasar.concurrent.NamedDaemonThreadFactory
import quasar.contrib.proxy.Search

import org.asynchttpclient.proxy.{ProxyServer, ProxyServerSelector}
import org.asynchttpclient.uri.Uri
import org.asynchttpclient.{AsyncHttpClientConfig, DefaultAsyncHttpClientConfig}

import org.http4s.client.Client
import org.http4s.client.asynchttpclient.AsyncHttpClient

import org.slf4s.Logging

import java.net.{InetSocketAddress, ProxySelector}
import java.net.Proxy
import java.net.Proxy.{Type => ProxyType}

import scala.collection.JavaConverters._

import cats.effect.{ConcurrentEffect, Resource}

object AsyncHttpClientBuilder extends Logging {
  def apply[F[_]: ConcurrentEffect]: Resource[F, Client[F]] =
    Resource.eval(Search[F]).flatMap(selector =>
      AsyncHttpClient.resource(mkConfig(selector)))

  def mkConfig[F[_]](proxySelector: ProxySelector): AsyncHttpClientConfig =
    new DefaultAsyncHttpClientConfig.Builder()
      .setMaxConnectionsPerHost(200)
      .setMaxConnections(400)
      .setRequestTimeout(Int.MaxValue)
      .setReadTimeout(Int.MaxValue)
      .setConnectTimeout(Int.MaxValue)
      .setProxyServerSelector(ProxyVoleProxyServerSelector(proxySelector))
      .setThreadFactory(new NamedDaemonThreadFactory("http4s-async-http-client-worker"))
      .build()

  private[s3] def sortProxies(proxies: List[Proxy]): List[Proxy] =
    proxies.sortWith((l, r) => (l.`type`, r.`type`) match {
      case (ProxyType.HTTP, ProxyType.DIRECT) => true
      case (ProxyType.SOCKS, ProxyType.DIRECT) => true
      case _ => false
    })

  private case class ProxyVoleProxyServerSelector(selector: ProxySelector)
      extends ProxyServerSelector {
    def select(uri: Uri): ProxyServer = {
      ProxySelector.setDefault(selector) // NB: I don't think this is necessary

      Option(selector)
        .flatMap(s => Option(s.select(uri.toJavaNetURI)))
        .flatMap(proxies0 => {
          val proxies = proxies0.asScala.toList
          log.debug(s"Found proxies: $proxies")

          val sortedProxies = sortProxies(proxies)
          log.debug(s"Prioritized proxies as: $sortedProxies")

          sortedProxies.headOption
        })
        .flatMap(server => Option(server.address))
        .map(_.asInstanceOf[InetSocketAddress]) // because Java
        .map(uriToProxyServer)
        .orNull // because Java x2
    }

    private def uriToProxyServer(u: InetSocketAddress): ProxyServer =
      (new ProxyServer.Builder(u.getHostName, u.getPort)).build
  }
}

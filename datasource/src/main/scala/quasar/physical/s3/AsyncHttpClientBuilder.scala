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

import quasar.contrib.proxy.Search

import org.asynchttpclient.proxy.{ProxyServer, ProxyServerSelector}
import org.asynchttpclient.uri.Uri
import org.asynchttpclient.{AsyncHttpClientConfig, DefaultAsyncHttpClientConfig}

import org.http4s.client.Client
import org.http4s.client.asynchttpclient.AsyncHttpClient
import org.http4s.util.threads.threadFactory

import java.net.{InetSocketAddress, ProxySelector}

import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._

import cats.effect.{ConcurrentEffect, Resource}

object AsyncHttpClientBuilder extends Http4sClientBuilder {
  def apply[F[_]: ConcurrentEffect](implicit ec: ExecutionContext): Resource[F, Client[F]] =
    Resource.liftF(Search[F]).flatMap(selector =>
      AsyncHttpClient.resource(mkConfig(selector)))

  def mkConfig[F[_]](proxySelector: ProxySelector): AsyncHttpClientConfig =
    new DefaultAsyncHttpClientConfig.Builder()
      .setMaxConnectionsPerHost(200)
      .setMaxConnections(400)
      .setRequestTimeout(Int.MaxValue)
      .setReadTimeout(Int.MaxValue)
      .setConnectTimeout(Int.MaxValue)
      .setProxyServerSelector(ProxyVoleProxyServerSelector(proxySelector))
      .setThreadFactory(threadFactory(name = { i =>
        s"http4s-async-http-client-worker-${i}"
      })).build()

  private case class ProxyVoleProxyServerSelector(selector: ProxySelector)
      extends ProxyServerSelector {
    def select(uri: Uri): ProxyServer = {
      ProxySelector.setDefault(selector)    // NB: I don't think this is necessary

      Option(selector)
        .flatMap(s => Option(s.select(uri.toJavaNetURI)))
        .flatMap(_.asScala.toList.headOption)
        .flatMap(server => Option(server.address))
        .map(_.asInstanceOf[InetSocketAddress])  // because Java
        .map(uriToProxyServer)
        .orNull // because Java x2
    }

    private def uriToProxyServer(u: InetSocketAddress): ProxyServer =
      (new ProxyServer.Builder(u.getHostName, u.getPort)).build
  }
}
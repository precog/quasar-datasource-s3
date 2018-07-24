# Quasar S3 Datasource

[![Build Status](https://travis-ci.org/slamdata/quasar-s3.svg?branch=master)](https://travis-ci.org/slamdata/quasar-s3)

A datasource for the Quasar open source analytics engine, that
provides access to Amazon S3.

## How to use this

1. Clone this repository (`git@github.com:slamdata/quasar-s3.git`)
2. At the root of the repo, run `./sbt assembleDatasource`. This will generate a tarball with a loadable `slamdata-backend` plugin
in `.targets/datasource/scala-2.12/quasar-s3-<version>-explode.tar.gz`
3. Extract the tarball to SlamData Backend's plugin directory. By default that is `$HOME/.config/slamdata/plugin/`
4. Run SlamData backend and the datasource should be available

## Configuration

You can create a new S3 datasource after you've loaded this plugin into
Quasar. Refer to the previous section for instructions on how to do
that. In order to create a datasource, you will need to send a POST
request to `/datasource` including a JSON
document specifiying the datasource's configuration. The format of the
JSON document can be found in [`slamdata-backend`'s
documentation.](https://github.com/slamdata/slamdata-backend#applicationvndslamdatadatasource). 

The connector-specific configuration needs to specify at least a
bucket URI and the JSON parsing to use when decoding JSON files stored
in S3. An example of a JSON configuration to create a datasource that
parses line-delimited JSON:

```json
{
  "bucket": "https://yourbucket.s3.amazonaws.com",
  "jsonParsing": "lineDelimited"
}
```

As another example, this is a JSON configuration to parse array
JSON:

```json
{
  "bucket": "https://yourbucket.s3.amazonaws.com",
  "jsonParsing": "array"
}
```

Along with the request, you also need to specify a `Content-Type` header: 

```
Content-Type: application/vnd.slamdata.datasource"
```

### Secure buckets

If your bucket is not public you need to include a `credentials`
subdocument with the credentials you use to access the bucket. For
example:

```
{
  "bucket":"https://some.bucket.uri",
  "jsonParsing":"array",
  "credentials": {
    "accessKey":"some access key",
    "secretKey":"super secret key",
    "region":"us-east-1"
  }
}
```

`accessKey`, `secretKey`, and `region` are all mandatory. You may omit
`credentials` entirely if your bucket is public. As with public
buckets, you need to include a `Content-Type` header. Refer to the previous
section for an example.

### Running the test suite for secure buckets

You need to decode the base64-encoded credentials.

For GNU `base64`:

```
base64 -d testCredentials.json.b64 > testCredentials.json
```

For BSD (or macOS) `base64`:

```
base64 -D -i testCredentials.json.b64 -o testCredentials.json
```

After this, you should be able to run the `SecureS3DataSourceSpec` spec


## Thanks to Sponsors

YourKit supports open source projects with its full-featured Java Profiler. YourKit, LLC is the creator of <a href="https://www.yourkit.com/java/profiler/index.jsp">YourKit Java Profiler</a> and <a href="https://www.yourkit.com/.net/profiler/index.jsp">YourKit .NET Profiler</a>, innovative and intelligent tools for profiling Java and .NET applications.

## Legal

Copyright &copy; 2014 - 2018 SlamData Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

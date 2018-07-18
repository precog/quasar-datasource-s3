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

You can create a new S3 datasource after you've loaded the plugin into
Quasar. Refer to the previous section for instructions on how to do
that. In order to create a datasource, you will need to send a PUT
request to `/datasource/<your datasource name>` including a JSON
document specifiying the datasource's configuration. An example of a
JSON document to create a datasource:

```json
{
  "bucket": "https://qsecure.s3.amazonaws.com",
  "jsonParsing": "lineDelimited"
}
```

`jsonParsing` can be either `lineDelimited` to parse JSON documents
separated by newlines or `array` to parse JSON documents held in a
JSON array. You'll need to specify a `Content-Type` header with the
information regarding this datasource. For example, to use version 1
of this datasource you may specify:

```
Content-Type: application/vnd.slamdata.datasource.s3; version="1"
```


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

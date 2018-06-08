# Quasar S3 Connector

[![Build Status](https://travis-ci.org/slamdata/quasar-s3.svg?branch=master)](https://travis-ci.org/slamdata/quasar-s3)

A connector for the Quasar open source analytics engine, that
provides access to Amazon S3.

Also documents how to create a lightweight connector for
quasar.

## The API

*All of this is subject to change, and **will** be changed in the near future.*

S3-specific documentation of the connector API can be found
[here](lwc/src/main/scala/quasar/physical/s3/S3LWC.scala),
and [here](lwc/src/main/scala/quasar/physical/s3/S3LWFS.scala).

Documentation of the implementation can be found
[here](lwc/src/main/scala/quasar/physical/s3/impl).

The API is separated into two stages:

1. The lightweight filesystem API (`LightweightFileSystem`)
which provides the filesystem operations that Quasar relies on.
2. The lightweight connector API (`LightweightConnector`) which
provides lightweight filesystems when configured.

For S3, you can think of each filesystem as a bucket, and
each connector as associating a bucket's URI with the bucket
itself.

### Lightweight Filesystems

Lightweight filesystems are similar to everyday filesystems.
They have notions of "path", "folder" and "file" which are
analogous to paths, folders and files on your local
filesystem. We use `scala-pathy` to represent paths, which
provides separate types for file paths and folder paths.

Lightweight filesystems provide three operations:
- `children`
- `read`
- `exists`

`children` is comparable to POSIX `ls`, and Windows `dir`; it
lists the files and folders in a directory.

`read` is comparable to POSIX `cat`, except that it reads files
as JSON rather than plain text.

`exists` checks if a file exists on a filesystem.

### Lightweight Connectors

Lightweight connectors provide a single method, `init`,
which takes a `ConnectionUri` and returns
a `LightweightFileSystem`. Note that `ConnectionUri` doesn't
have to be a valid URI; you can include any kind of
configuration data inside as well.

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

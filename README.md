# Quasar S3 Datasource

## Usage

```sbt
libraryDependencies += "com.precog" %% "quasar-datasource-s3" % <version>
```

## Configuration

The configuration of the S3 datasource has the following JSON format

```
{
  "bucket": String,
  "format": {
    "type": "json" | "separated-values"
    // for "json"
    "precise": Boolean,
    "variant" "array-wrapped" | "line-delimited"
    // for "separated-values", all strings must be one symbol length
    "header": Boolean,
    // The first char of row break
    "row1": String,
    // The second char of row break, empty string if row break has only one symbol
    "row2": String,
    // Column separator (char)
    "record": String,
    "openQuote": String,
    "closeQuote": String,
    "escape": String
  },
  ["compressionScheme": "gzip" | "zip"]
  ["credentials": Object]
}
```

* `bucket` the URL of the S3 bucket to use, e.g. `https://yourbucket.s3.amazonaws.com`
* `format` the format of the resource referred to by `url`. CSV/TSV, array wrapped json and line delimited jsons are supported
* `compressionScheme` (optional, default = empty) compression scheme that the resources in the container are assumed
  to be compressed with. Currrently `"gzip"` and `"zip"` are supported.
  If omitted, the resources are not assumed to be compressed.
* `credentials` (optional, default = empty) S3 credentials to use for access in case the bucket is not public.
  Object has the following format: `{ "accessKey": String, "secretKey": String, "region": String }`.
  The `credentials` section can be omitted completely for public buckets, but for private buckets the section needs
  to be there with all 3 fields specified.

Example:

```
{
  "bucket": "https://yourbucket.s3.amazonaws.com",
  "format": {"type": "json", "variant": "line-delimited", "precise": false},
  "jsonParsing": "array",
  "compressionScheme": "gzip",
  "credentials": {
    "accessKey": "some access key",
    "secretKey": "super secret key",
    "region": "us-east-1"
  }
}
```

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

After this, you should be able to run the `SecureS3DatasourceSpec` spec

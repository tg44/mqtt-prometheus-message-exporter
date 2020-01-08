# MQTT Prometheus Message Exporter

It's a small service which will convert mqtt messages to prometheus metrics.

## How it works:

For getting mqtt messages we need to subscribe to topics, and also to parse and convert the messages to metrics we need some "pattern" syntax.

#### Path generation
For any given pattern the app will figure out which topic its need to subscribe. After the subscribes it will try to parse messages as jsons.
If the given result is a single number that is our "measure". If the given result is a json-object, we flatten that object.
At each message we will get a list of "path" + value. 

For example the topic is `my/thermostat`, and the json message is:
```json
{
  "num": 10,
  "inner": {
    "num": 5,
    "arr": [1, 2, 3],
    "str": "test",
    "bool": false,
    "null": null
  },
  "so": {
    "deep": {
      "really": {
        "really": 8
      }
    }
  }
}
```
We will end up with these paths:
```
"my/thermostat/num" -> 10, 
"my/thermostat/inner/num" -> 5, 
"my/thermostat/so/deep/really/really" -> 8
```

#### Pattern to prometheus metric
The paths can be matched and converted to prometheus metrics with three operators:
 - `[[prefix]]` will use the matching segment in the metric name
 - `[[prefixes]]` should be only in tail position and it will use all the remaining segments in the metric name
 - `<<label>>` will create a label with the name name of `label` and the walue will be the path sagment
 
Examples for the path of `my/thermostat/so/deep/really/really`: 
 - `my/[[segment]]/so/deep/really/really` will be a metric named `thermostat`
 - `my/<<device>>/[[segment]]/deep/really/[[segment]]` will be a metric named `so_really` with a label `{device=thermostat}`
 - `my/<<device>>/[[segment]]/deep` will not match and will be dropped
 - `my/<<device>>/[[segment]]/deep/[[segments]]` will be a metric named `so_really_really` with a label `{device=thermostat}`

#### Subscribe optimization syntax in patterns:
The app will try to optimize the subscriptions. If you add the pattern `my/thermostat` it will subscribe to `my/#`.
If you have a pattern like `my/<<device>>/[[segment]]/deep/[[segments]]` and you only interested the topics which has three segments you can help with adding a pipe segment.

Examples:
 - `my/<<device>>/[[segment]]/deep/[[segments]]` subscribes to `my/#`
 - `my/<<device>>/[[segment]]/|/deep/[[segments]]` subscribes to `my/+/+` (and will match like the previous one)

#### Other notes:
 - You should add a prefix to every pattern (check the config)!
   - So a metrics will looks like `iot_pressure` instead of just `preasure`
 - The special characters will be cut out from topic names or json keys (before they would appear in the prometheus output).
 - The empty segments (after the special char removal) will be dropped.
 - Spaces will convert to `_`
 - Topic names and json keys will be lower-cased both in prometheus names and labels
 - Patterns will match "as-is" so if you have special characters in the topic names and you want to match to them you should use those characters on topic names too!
 - `/` in topic names, or in json keys are untested and I think it should not work.
 - `|` is a bad topic name or json key, pls don't use it and the parser will be happy :D
 - topicnames with starting `<<` and ending `>>` are cursed, you can match them as a segment
 - topicnames with the exact `[[segment]]` and `[[segments]]` name are cursed too, they will go to the metric name as `segment` and `segments`
 - if you have other use-case or idea pls open an issue 

## Config:
For example config check the example.conf!

For working (and non working) pattern examples check the TopicParserSpec file,
 under the tests (or read the upper sections to understand them)!

## Docker usage example:
You need to mount up a config file, and set the `CONF_PATH` env var to that location.

Compose file example:
```
mqtt-prom-exp:
    restart: unless-stopped
    image: tg44/mqtt-prometheus-message-exporter
    volumes:
      - /hdd/docker/mosquitto/config/exporter.conf:/app/exporter.conf
    ports:
      - "9324:9000"
    environment:
      - 'CONF_PATH=/app/exporter.conf'
```

With the config above the metrics will be available at `localhost:9324/metrics`.


## Contribution
If you have any idea about the base functionality or the config/pattern syntax, just start a new issue/pr and we can talk about the use-cases, pros and cons!

## Prometheus exporter implementation
I was trigger happy at the beginning and I implemented a closely complete prometheus exporter lib. 
That is not used in the running app (or just a really small amount of it). If you are interested in a scala-prometheus exporter contact me (mail or twitter, or issue are both ok)!


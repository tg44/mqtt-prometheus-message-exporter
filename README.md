# MQTT Prometheus Message Exporter

It's a small service which will convert mqtt messages to prometheus metrics.

For example config check the example.conf!

You need to mount up a config file, and set the `CONF_PATH` env var to that location

For pattern examples Check the TopicParserSpec file under the tests!

TODOs:
 - write better doc
 - clean up the code
 - add docker examples

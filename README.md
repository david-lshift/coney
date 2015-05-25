Coney
=====
Copyright (c) 2015 Tom Parker, LShift Ltd

[![Build Status](https://travis-ci.org/lshift/coney.svg?branch=master)](https://travis-ci.org/lshift/coney) [![Coverage Status](https://coveralls.io/repos/lshift/coney/badge.svg?branch=master)](https://coveralls.io/r/lshift/coney?branch=master)

Makes sure a particular RabbitMQ instance has a particular set of queues/exchanges/users/etc. See `rabbit-config.edn` for an example config file that will setup everything needed by the other tools.

Usage: `coney <config file> <options>`

| Argument       | Default   |
| ----------     | -------   |
|  -h, --help    |           |
|  --host        | localhost |
| -f, --filetype | edn/json  |

# Coney

Copyright (c) 2015 Tom Parker, LShift Ltd

[![Build Status](https://travis-ci.org/lshift/coney.svg?branch=master)](https://travis-ci.org/lshift/coney) [![Coverage Status](https://coveralls.io/repos/lshift/coney/badge.svg?branch=master)](https://coveralls.io/r/lshift/coney?branch=master)

Makes sure a particular RabbitMQ instance has a particular set of queues/exchanges/users/etc. See `rabbit-config.edn` for an example config file that will setup everything needed by the other tools.

## Usage

Usage: `coney <config file> <options>`

| Argument       | Default   |
| ----------     | -------   |
|  -h, --help    |           |
|  --host        | localhost |
| -f, --filetype | edn/json  |

## License
This program is free software; you can redistribute it and/or modify it under the terms of the [Mozilla Public License Version 1.1](LICENSE) (the "License")

Software distributed under the License is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for the specific language governing rights and limitations under the License.

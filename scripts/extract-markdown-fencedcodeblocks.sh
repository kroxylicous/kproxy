#!/usr/bin/env bash
#
# Copyright Kroxylicious Authors.
#
# Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

# Extracts fenced code blocks from the given markdown supplied on stdin, emitting them to stdout.
# If the fenced code block has an attribute `comment` its contents are emitted too, before the codeblock to which it is
# applied.
# Supports only codeblocks delimited by backticks.

set -euo pipefail

BLOCKTYPE=${BLOCKTYPE:-shell}

gawk \
'BEGIN{codeblock = 0}

     /^ *```'${BLOCKTYPE}'/       { codeblock=1; if (match($0, /comment="([^"]*)"/, m)) { print m[1]}; next; }
     codeblock && /^ *```/        { codeblock=0; next; }
     codeblock                    {gsub(/^ */, "", $0); print}
'

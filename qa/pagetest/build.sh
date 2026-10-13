#!/bin/zsh
# build.sh: compiles runner.swift to ./runner (needs the Xcode command line tools)
here=${0:A:h}
swiftc -O $here/runner.swift -o $here/runner && echo "built $here/runner"

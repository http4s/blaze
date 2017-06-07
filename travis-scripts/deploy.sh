#!/bin/sh

sudo chmod +x /usr/local/bin/sbt # Temporary Fix For https://github.com/travis-ci/travis-ci/issues/7703

if [ $TRAVIS_PULL_REQUEST != 'false' ]
then
    echo "Only Repository Merged Code is Released"

elif [ $TRAVIS_BRANCH != "master" ]
then
    echo "Only Master Branch is Released"
else
   sbt ++$TRAVIS_SCALA_VERSION 'release with-defaults'
fi


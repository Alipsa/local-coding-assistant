#!/usr/bin/env bash
if [[ $(git status --porcelain) ]]; then
  echo "Git changes detected, commit all changes first before releasing"
  exit
fi
mvn -Prelease -B clean site deploy || exit 1
echo "Release successful!"
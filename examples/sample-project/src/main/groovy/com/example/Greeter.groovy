package com.example

import groovy.transform.CompileStatic

@CompileStatic
class Greeter {

  String greet(String name) {
    String safe = name?.trim()
    if (!safe) {
      safe = "World"
    }
    "Hello, ${safe}!"
  }
}

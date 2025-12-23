package com.example

import groovy.transform.CompileStatic
import spock.lang.Specification

@CompileStatic
class GreeterSpec extends Specification {

  def "greet returns default when name is blank"() {
    given:
    Greeter greeter = new Greeter()

    expect:
    greeter.greet("  ") == "Hello, World!"
  }

  def "greet trims name"() {
    given:
    Greeter greeter = new Greeter()

    expect:
    greeter.greet(" Ada ") == "Hello, Ada!"
  }
}

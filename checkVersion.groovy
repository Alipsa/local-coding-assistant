#!/usr/bin/env groovy

import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI

def checkVersion(String dependency) {
    // Parse the dependency string
    def parts = dependency.split(':')
    if (parts.length < 2 || parts.length > 3) {
        throw new IllegalArgumentException("Invalid dependency format. Expected: group:artifact:version or group:artifact")
    }
    
    def group = parts[0]
    def artifact = parts[1]
    def version = parts.length == 3 ? parts[2] : null
    
    def baseUrl = "https://search.maven.org/solrsearch/select"
    def query = "g:\"${group}\" AND a:\"${artifact}\""
    
    def client = HttpClient.newHttpClient()
    def request = HttpRequest.newBuilder()
        .uri(URI.create("${baseUrl}?q=${query}&core=central&wt=json"))
        .GET()
        .build()
    
    def response = client.send(request, HttpResponse.BodyHandlers.ofString())
    
    if (response.statusCode() != 200) {
        throw new RuntimeException("Failed to fetch data from Maven Central. Status: ${response.statusCode()}")
    }
    
    def jsonSlurper = new JsonSlurper()
    def jsonResponse = jsonSlurper.parseText(response.body())
    
    if (jsonResponse.response.numFound == 0) {
        println("No artifacts found for ${group}:${artifact}")
        return
    }
    
    // Get the latest version (the one with highest version number)
    def latestVersion = jsonResponse.response.docs[0].latestVersion
    
    if (version == null) {
        // No version specified, just show the latest version
        println("${group}:${artifact}:${latestVersion}")
    } else {
        // Version specified, check if it's the latest
        if (version == latestVersion) {
            println("${group}:${artifact}:${version} (latest)")
        } else {
            println("${group}:${artifact}:${version} (latest: ${latestVersion})")
        }
    }
}

// Main execution
if (args.length == 0) {
    println("Usage: groovy checkVersion.groovy \"group:artifact:version\"")
    println("       groovy checkVersion.groovy \"group:artifact\"")
    System.exit(1)
}

try {
    checkVersion(args[0])
} catch (Exception e) {
    println("Error: ${e.message}")
    System.exit(1)
}

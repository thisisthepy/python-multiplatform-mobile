package org.thisisthepy.python.multiplatform.demo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
package org.thisisthepy.python.multiplatform

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

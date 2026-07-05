package com.jdcr.jdcrhttp.util

import com.jdcr.jdcrlog.JdcrLogBase

object JdcrHttpLog : JdcrLogBase() {

    init {
        setDefaultTag("http")
    }

    fun wd(wMessage: String, dMessage: String) {
        w(wMessage)
        d(dMessage)
    }

}
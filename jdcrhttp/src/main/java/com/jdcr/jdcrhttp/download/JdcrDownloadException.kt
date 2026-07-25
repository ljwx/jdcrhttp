package com.jdcr.jdcrhttp.download

class JdcrDownloadProtocolException(message: String) : IllegalStateException(message)

class JdcrDownloadChecksumException(
    val expected: String,
    val actual: String,
) : IllegalStateException("SHA-256 校验失败，expected=$expected, actual=$actual")

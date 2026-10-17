package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode

internal fun ok(raw: String): ParseResult.Ok = PayloadParser.parse(raw) as ParseResult.Ok

internal fun SignalAnalyzer.on(raw: String): List<Signal> = analyze(ok(raw))

internal fun List<Signal>.codes(): Set<SignalCode> = map { it.code }.toSet()

internal fun List<Signal>.of(code: SignalCode): Signal = single { it.code == code }

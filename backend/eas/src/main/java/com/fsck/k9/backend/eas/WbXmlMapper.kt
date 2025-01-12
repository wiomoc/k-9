package com.fsck.k9.backend.eas

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.ParameterizedType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KFunction
import kotlin.reflect.full.primaryConstructor

/**
 * Used to annotate a field of a `data class` as a WbXml element.
 * @param value contains the Tag
 * @param index orders the properties. A field annotated with index `0` would be the first property,
 * the first constructor parameter and the first inner element in the outer element
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
annotation class Tag(val value: Int, val index: Int = 0)

interface StreamableElement {
    fun readFromStream(inputStream: InputStream)
    fun writeToStream(outputStream: OutputStream)
}

/**
 * Converts a Kotlin `data class` instance to a WbXml @see (WAP Binary XML Content Format)[https://www.w3.org/TR/wbxml/]
 * document and vice versa.
 */
object WbXmlMapper {
    const val EOL = -1

    private val fieldSerializers = mutableMapOf<Class<*>, List<(Any, OutputStream, AtomicInteger) -> Unit>>()

    private fun getFieldSerializers(clazz: Class<*>): List<(Any, OutputStream, AtomicInteger) -> Unit> {
        var serializer = fieldSerializers[clazz]
        if (serializer == null) {
            serializer = clazz.declaredFields
                    .map { it to it.getAnnotation(Tag::class.java) }
                    .filter { it.second != null }
                    .sortedBy { it.second.index }
                    .map {
                        val field = it.first
                        val tag = it.second.value
                        field.isAccessible = true

                        if (field.type == List::class.java) {
                            { obj: Any,
                              output: OutputStream,
                              tagPage: AtomicInteger ->
                                field.get(obj)?.let {
                                    (it as List<*>).forEach {
                                        writeElement(output, tag, it!!, tagPage)
                                    }
                                }
                                Unit
                            }
                        } else {
                            { obj: Any,
                              output: OutputStream,
                              tagPage: AtomicInteger ->
                                field.get(obj)?.let {
                                    writeElement(output, tag, it, tagPage)
                                }
                                Unit
                            }
                        }
                    }
            fieldSerializers[clazz] = serializer
        }
        return serializer
    }

    fun serialize(obj: Any, output: OutputStream) = output.run {
        write(0x03) // version 1.3
        write(0x01) // unknown or missing public identifier
        write(106)
        write(0)
        serializeInner(obj, this, AtomicInteger(2220))
        flush()
    }

    private fun serializeInner(
            obj: Any,
            output: OutputStream,
            tagPage: AtomicInteger
    ) {
        getFieldSerializers(obj.javaClass).forEach {
            it(obj, output, tagPage)
        }
    }

    private fun writeElement(output: OutputStream, tag: Int, value: Any, tagPage: AtomicInteger) {
        output.run {
            val page = tag shr WbXml.PAGE_SHIFT
            val id = tag and WbXml.PAGE_MASK
            if (page != tagPage.get()) {
                tagPage.set(page)
                write(WbXml.SWITCH_PAGE)
                write(page)
            }
            if (value is Boolean) {
                if (value) {
                    // Write 'stag' empty element
                    write(id)
                }
            } else {
                // Write 'stag' non element
                write(id or WbXml.CONTENT_MASK)

                when (value) {
                    is String -> {
                        write(WbXml.STR_I)
                        write(value.toByteArray())
                        write(0)
                    }
                    is Int -> {
                        write(WbXml.STR_I)
                        write(value.toString().toByteArray())
                        write(0)
                    }
                    is StreamableElement -> {
                        write(WbXml.STR_I)
                        value.writeToStream(this)
                        write(0)
                    }
                    else -> serializeInner(value, output, tagPage)
                }
                write(WbXml.END)
            }
        }
    }

    private val fieldParsers =
            mutableMapOf<Class<*>, Pair<KFunction<*>, Map<Int, (InputStream, AtomicInteger, Array<Any?>, Boolean) -> Unit>>>()

    /**
     * @return [Pair] of, first the constructor to build the `data class` instance from the parsed inner elements,
     * second [Map] of Tag to function which takes the [InputStream], the current tag page ,
     * array holding the inner elements, boolean if the tag is empty
     */
    private fun getFieldParsers(clazz: Class<*>): Pair<KFunction<*>, Map<Int, (InputStream, AtomicInteger, Array<Any?>, Boolean) -> Unit>> {
        var fieldParser = fieldParsers[clazz]
        if (fieldParser == null) {
            val constructor = clazz.kotlin.primaryConstructor!!

            fieldParser = constructor to clazz.declaredFields
                    .map { it to it.getAnnotation(Tag::class.java) }
                    .filter { it.second != null }
                    .map {
                        val field = it.first
                        val tag = it.second.value
                        val index = it.second.index
                        val parser = if (field.type == List::class.java) {
                            val type = (field.genericType as ParameterizedType).actualTypeArguments[0] as Class<*>
                            when (type) {
                                String::class.java -> {
                                    { input: InputStream,
                                      _: AtomicInteger,
                                      params: Array<Any?>,
                                      isEmpty: Boolean ->
                                        if (params[index] == null) {
                                            params[index] = ArrayList<String>()
                                        }
                                        if (!isEmpty) {
                                            @Suppress("UNCHECKED_CAST")
                                            (params[index] as ArrayList<String>).add(parseString(input))
                                        }
                                        Unit
                                    }
                                }
                                Int::class.java, Integer::class.java -> {
                                    { input: InputStream,
                                      _: AtomicInteger,
                                      params: Array<Any?>,
                                      isEmpty: Boolean ->
                                        if (params[index] == null) {
                                            params[index] = ArrayList<Int>()
                                        }
                                        if (!isEmpty) {
                                            @Suppress("UNCHECKED_CAST")
                                            (params[index] as ArrayList<Int>).add(parseString(input).toInt())
                                        }
                                        Unit
                                    }
                                }
                                else -> {
                                    { input: InputStream,
                                      tagPage: AtomicInteger,
                                      params: Array<Any?>,
                                      isEmpty: Boolean ->
                                        if (params[index] == null) {
                                            params[index] = ArrayList<Any?>()
                                        }
                                        if (!isEmpty) {
                                            @Suppress("UNCHECKED_CAST")
                                            (params[index] as ArrayList<Any?>).add(parseInner(input, type, tagPage))
                                        }
                                        Unit
                                    }
                                }
                            }
                        } else {
                            when (field.type) {
                                String::class.java -> {
                                    { input: InputStream,
                                      _: AtomicInteger,
                                      params: Array<Any?>,
                                      isEmpty: Boolean ->
                                        if (isEmpty) {
                                            params[index] = ""
                                        } else {
                                            params[index] = parseString(input)
                                        }
                                        Unit
                                    }
                                }
                                Int::class.java, Integer::class.java -> {
                                    { input: InputStream,
                                      _: AtomicInteger,
                                      params: Array<Any?>,
                                      isEmpty: Boolean ->
                                        if (isEmpty) {
                                            params[index] = 0
                                        } else {
                                            params[index] = parseString(input).toInt()
                                        }
                                        Unit
                                    }
                                }
                                java.lang.Boolean::class.java, Boolean::class.java -> {
                                    { _: InputStream,
                                      _: AtomicInteger,
                                      params: Array<Any?>,
                                      _: Boolean ->
                                        params[index] = true
                                        Unit
                                    }
                                }
                                else -> {
                                    if (StreamableElement::class.java.isAssignableFrom(field.type)) {
                                        @Suppress("UNCHECKED_CAST")
                                        val streamableElementConstructor = field.type.kotlin.primaryConstructor
                                                as KFunction<StreamableElement>

                                        { input: InputStream,
                                          _: AtomicInteger,
                                          params: Array<Any?>,
                                          isEmpty: Boolean ->
                                            if (!isEmpty) {
                                                params[index] = parseStreamableElement(input, streamableElementConstructor)
                                            }
                                            Unit
                                        }
                                    } else {
                                        { input: InputStream,
                                          tagPage: AtomicInteger,
                                          params: Array<Any?>,
                                          isEmpty: Boolean ->
                                            if (!isEmpty) {
                                                params[index] = parseInner(input, field.type, tagPage)
                                            }
                                            Unit
                                        }
                                    }
                                }
                            }
                        }
                        tag to parser
                    }.toMap()

            fieldParsers[clazz] = fieldParser
        }
        return fieldParser
    }

    inline fun <reified T : Any> parse(input: InputStream) = parse(input, T::class.java)

    fun <T : Any> parse(input: InputStream, clazz: Class<T>) =
            input.use {
                it.run {
                    read() // version
                    consumeInt()  // ?
                    consumeInt()  // 106 (UTF-8)
                    consumeInt()  // string table length

                    parseInner(this, clazz, AtomicInteger(0))
                }
            }

    private fun <T : StreamableElement> parseStreamableElement(input: InputStream, constructor: KFunction<T>): T? {
        input.run {
            val id = read()
            val streamableElement = when (id) {
                WbXml.STR_I -> {
                    val streamableElement = constructor.call()

                    streamableElement.readFromStream(object : InputStream() {
                        var closed = false
                        override fun read(): Int {
                            if (closed) {
                                return EOL
                            }
                            return when (val i = input.read()) {
                                0 -> {
                                    closed = true
                                    EOL
                                }
                                EOL -> throw IOException("Unexpected EOF")
                                else -> i
                            }
                        }
                    })

                    streamableElement
                }
                WbXml.END -> return null
                else -> throw IllegalStateException(id.toString())
            }
            when (read()) {
                WbXml.END -> return streamableElement
                else -> throw IllegalStateException()
            }
        }
    }

    private fun parseString(input: InputStream): String {
        input.run {
            val id = read()
            val string = when (id) {
                WbXml.STR_I -> {
                    val outputStream = ByteArrayOutputStream(256)
                    str@ while (true) {
                        val i = read()
                        when (i) {
                            0 -> break@str
                            EOL -> throw IOException("Unexpected EOF")
                            else -> outputStream.write(i)
                        }
                    }
                    outputStream.toString("UTF-8")
                }
                WbXml.END -> return ""
                else -> throw IllegalStateException(id.toString())
            }
            when (read()) {
                WbXml.END -> return string
                else -> throw IllegalStateException()
            }
        }
    }

    private fun skipTag(input: InputStream, tagPage: AtomicInteger) {
        input.run {
            while (true) {
                val id = read()
                when (id) {
                    WbXml.SWITCH_PAGE -> {
                        tagPage.set(read() shl WbXml.PAGE_SHIFT)
                    }
                    WbXml.END -> {
                        return
                    }
                    WbXml.STR_I -> {
                        while (read() != 0) {
                        }
                    }
                    EOL -> {
                        throw IOException("Unexpected EOF")
                    }
                    else -> if (id and WbXml.CONTENT_MASK != 0) {
                        skipTag(input, tagPage)
                    }
                }
            }
        }
    }

    private fun <T : Any> parseInner(input: InputStream, clazz: Class<T>, tagPage: AtomicInteger): T {
        val (constructor, mapping) = getFieldParsers(clazz)

        val params = Array<Any?>(mapping.size) { null }

        input.run {
            while (true) {
                val id = read()
                when (id) {
                    WbXml.SWITCH_PAGE -> {
                        tagPage.set(read() shl WbXml.PAGE_SHIFT)
                    }
                    WbXml.END -> {
                        //try {
                        @Suppress("UNCHECKED_CAST")
                        return constructor.call(*params) as T
                        //} catch (e: Exception) {
                        //   IOException("Required Element missing")
                        //}
                    }
                    WbXml.STR_I -> {
                        throw IOException("Unexpected STR_I")
                    }
                    EOL -> {
                        // try {
                        @Suppress("UNCHECKED_CAST")
                        return constructor.call(*params) as T
                        // } catch (e: Exception) {
                        //    IOException("Required Element missing")
                        // }
                        // throw IOException("Unexpected EOF")
                    }
                    else -> {
                        val tag = id and WbXml.PAGE_MASK or tagPage.get()
                        if (id and WbXml.CONTENT_MASK != 0) {
                            mapping[tag]?.let { consumer ->
                                consumer(input, tagPage, params, false)
                            } ?: skipTag(this, tagPage)
                        } else {
                            mapping[tag]?.let { consumer ->
                                consumer(input, tagPage, params, true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun InputStream.consumeInt() {
        while (read() and 0x80 != 0) {
        }
    }
}

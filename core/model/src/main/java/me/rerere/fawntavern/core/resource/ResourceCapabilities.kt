package me.rerere.fawntavern.core.resource

/** 可按稳定名称列举和读取的资源。 */
interface NamedResourceReader<T> {
    suspend fun names(): List<String>
    suspend fun load(name: String): T
}

interface NamedResourceCreator<T> {
    suspend fun create(name: String): T
}

interface ResourceImporter<T, in Source> {
    suspend fun import(source: Source): T
}

interface ResourceRenamer<in Key> {
    suspend fun rename(old: Key, new: String): Boolean
}

interface ResourceDeleter<in Key> {
    suspend fun delete(key: Key)
}

/** 通用导入列表所需的最小控制器能力。 */
interface ImportableResourceController<T, in Source> {
    suspend fun names(): List<String>
    suspend fun load(name: String): T
    suspend fun import(source: Source): String
    suspend fun rename(old: String, new: String): Boolean
    suspend fun delete(name: String)
}

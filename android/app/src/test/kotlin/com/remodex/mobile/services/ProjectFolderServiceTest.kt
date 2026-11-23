package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCError
import com.remodex.mobile.core.model.RPCMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class ProjectFolderServiceTest {
    @Test
    fun recoveryUsesExistingPathWhenOnlyTheDirectoryBrowserDisallowsIt() = runTest {
        val failure = CodexServiceError.RpcFailure(RPCError(
            -32000, "Outside the directory browser", JSONValue.Obj(mapOf("errorCode" to JSONValue.Str("path_not_allowed"))),
        ))
        val service = ProjectFolderService(MinimalSendRepository { _, _ -> throw failure })
        var logged: CodexServiceError.RpcFailure? = null

        assertEquals("/data/project", service.resolveExistingWorkspace("/data/project") { logged = it })
        assertSame(failure, logged)
    }

    @Test
    fun recoveryPropagatesMissingDirectoryWithItsOriginalErrorCode() = runTest {
        val failure = CodexServiceError.RpcFailure(RPCError(
            -32000, "Directory is missing", JSONValue.Obj(mapOf("errorCode" to JSONValue.Str("missing_directory"))),
        ))
        val service = ProjectFolderService(MinimalSendRepository { _, _ -> throw failure })

        val actual = assertFailsWith<CodexServiceError.RpcFailure> {
            service.resolveExistingWorkspace("/home/project") { error("Directory is missing") }
        }
        assertSame(failure, actual)
    }

    @Test
    fun quickLocations_decodesBridgeLocations() =
        runTest {
            val service =
                ProjectFolderService(
                    MinimalSendRepository { method, params ->
                        assertEquals("project/quickLocations", method)
                        assertEquals(emptyMap(), (params as JSONValue.Obj).map)
                        RPCMessage.success(
                            id = null,
                            result =
                                JSONValue.Obj(
                                    mapOf(
                                        "locations" to
                                            JSONValue.Arr(
                                                listOf(
                                                    JSONValue.Obj(
                                                        mapOf(
                                                            "id" to JSONValue.Str("home"),
                                                            "label" to JSONValue.Str("Home"),
                                                            "path" to JSONValue.Str("/Users/me"),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        )
                    },
                )

            val locations = service.quickLocations()

            assertEquals("home", locations.single().id)
            assertEquals("/Users/me", locations.single().path)
        }

    @Test
    fun searchDirectories_sendsRootQueryAndLimit() =
        runTest {
            var captured: Map<String, JSONValue>? = null
            val service =
                ProjectFolderService(
                    MinimalSendRepository { method, params ->
                        assertEquals("project/searchDirectories", method)
                        captured = (params as JSONValue.Obj).map
                        RPCMessage.success(
                            id = null,
                            result =
                                JSONValue.Obj(
                                    mapOf(
                                        "entries" to
                                            JSONValue.Arr(
                                                listOf(
                                                    JSONValue.Obj(
                                                        mapOf(
                                                            "name" to JSONValue.Str("remodex"),
                                                            "path" to JSONValue.Str("/Users/me/remodex"),
                                                            "isSymlink" to JSONValue.Bool(false),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        )
                    },
                )

            val entries = service.searchDirectories("/Users/me", "remo")

            assertEquals("/Users/me", captured!!["path"]?.stringValue)
            assertEquals("remo", captured!!["query"]?.stringValue)
            assertEquals(80, captured!!["limit"]?.intValue)
            assertEquals("/Users/me/remodex", entries.single().path)
        }

    @Test
    fun createRootlessChatRoot_sendsPromptHintAndReturnsPath() =
        runTest {
            var captured: Map<String, JSONValue>? = null
            val service =
                ProjectFolderService(
                    MinimalSendRepository { method, params ->
                        assertEquals("project/createRootlessChatRoot", method)
                        captured = (params as JSONValue.Obj).map
                        RPCMessage.success(
                            id = null,
                            result =
                                JSONValue.Obj(
                                    mapOf(
                                        "path" to JSONValue.Str("C:\\Users\\me\\Documents\\Codex\\2026-05-28\\hello"),
                                    ),
                                ),
                        )
                    },
                )

            val path = service.createRootlessChatRoot(" hello ")

            assertEquals("hello", captured!!["promptHint"]?.stringValue)
            assertEquals("C:\\Users\\me\\Documents\\Codex\\2026-05-28\\hello", path)
        }
}

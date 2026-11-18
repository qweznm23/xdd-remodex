package com.remodex.mobile.services
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import java.util.concurrent.TimeoutException

class QuestionReplyDeadlineTest {
 @Test fun ownedDeadlineRetainsTimeoutCauseAndStage()=runTest {
  val error=assertFailsWith<TimeoutException>{questionReplyDeadline(50){delay(100);"late"}}
  assertTrue(error.cause is TimeoutCancellationException)
  assertTrue(error.message!!.contains("stage=question_reply_send"))
 }
 @Test fun parentCancellationAndInnerDeadlineAreNotReclassified()=runTest {
  val cancellation=CancellationException("left conversation")
  val actual=assertFailsWith<CancellationException>{questionReplyDeadline<String>{throw cancellation}}
  assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
  assertFailsWith<TimeoutCancellationException>{questionReplyDeadline(100){withTimeout(20){delay(50);"late"}}}
 }
}

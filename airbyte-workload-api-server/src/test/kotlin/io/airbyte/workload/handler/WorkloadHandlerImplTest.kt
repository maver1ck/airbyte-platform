package io.airbyte.workload.handler

import io.airbyte.featureflag.TestClient
import io.airbyte.workload.api.domain.WorkloadLabel
import io.airbyte.workload.errors.ConflictException
import io.airbyte.workload.errors.InvalidStatusTransitionException
import io.airbyte.workload.errors.NotFoundException
import io.airbyte.workload.handler.WorkloadHandlerImplTest.Fixtures.DATAPLANE_ID
import io.airbyte.workload.handler.WorkloadHandlerImplTest.Fixtures.WORKLOAD_ID
import io.airbyte.workload.handler.WorkloadHandlerImplTest.Fixtures.workloadHandler
import io.airbyte.workload.handler.WorkloadHandlerImplTest.Fixtures.workloadRepository
import io.airbyte.workload.repository.WorkloadRepository
import io.airbyte.workload.repository.domain.Workload
import io.airbyte.workload.repository.domain.WorkloadStatus
import io.airbyte.workload.repository.domain.WorkloadType
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

class WorkloadHandlerImplTest {
  val now = OffsetDateTime.now()

  @BeforeEach
  fun reset() {
    clearAllMocks()
    every { workloadHandler.offsetDateTime() }.returns(now)
  }

  @Test
  fun `test active statuses are complete`() {
    assertEquals(
      setOf(WorkloadStatus.PENDING, WorkloadStatus.CLAIMED, WorkloadStatus.LAUNCHED, WorkloadStatus.RUNNING),
      WorkloadHandlerImpl.ACTIVE_STATUSES.toSet(),
    )
    assertFalse(WorkloadHandlerImpl.ACTIVE_STATUSES.contains(WorkloadStatus.CANCELLED))
    assertFalse(WorkloadHandlerImpl.ACTIVE_STATUSES.contains(WorkloadStatus.FAILURE))
    assertFalse(WorkloadHandlerImpl.ACTIVE_STATUSES.contains(WorkloadStatus.SUCCESS))
  }

  @Test
  fun `test get workload`() {
    val domainWorkload =
      Workload(
        id = WORKLOAD_ID,
        dataplaneId = null,
        status = WorkloadStatus.PENDING,
        workloadLabels = null,
        inputPayload = "",
        logPath = "/",
        geography = "US",
        mutexKey = null,
        type = WorkloadType.SYNC,
        autoId = UUID.randomUUID(),
      )

    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.of(domainWorkload))
    val apiWorkload = workloadHandler.getWorkload(WORKLOAD_ID)
    assertEquals(domainWorkload.id, apiWorkload.id)
  }

  @Test
  fun `test workload not found`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.getWorkload(WORKLOAD_ID) }
  }

  @Test
  fun `test create workload`() {
    val workloadLabel1 = WorkloadLabel("key1", "value1")
    val workloadLabel2 = WorkloadLabel("key2", "value2")
    val workloadLabels = mutableListOf(workloadLabel1, workloadLabel2)

    every { workloadRepository.existsById(WORKLOAD_ID) }.returns(false)
    every { workloadRepository.searchByMutexKeyAndStatusInList("mutex-this", WorkloadHandlerImpl.ACTIVE_STATUSES) }.returns(listOf())
    every { workloadRepository.save(any()) }.returns(
      Fixtures.workload(),
    )
    workloadHandler.createWorkload(
      WORKLOAD_ID,
      workloadLabels,
      "input payload",
      "/log/path",
      "US",
      "mutex-this",
      io.airbyte.config.WorkloadType.SYNC,
      UUID.randomUUID(),
      now.plusHours(2),
    )
    verify {
      workloadRepository.save(
        match {
          it.id == WORKLOAD_ID &&
            it.dataplaneId == null &&
            it.status == WorkloadStatus.PENDING &&
            it.lastHeartbeatAt == null &&
            it.workloadLabels!!.get(0).key == workloadLabel1.key &&
            it.workloadLabels!!.get(0).value == workloadLabel1.value &&
            it.workloadLabels!!.get(1).key == workloadLabel2.key &&
            it.workloadLabels!!.get(1).value == workloadLabel2.value &&
            it.inputPayload == "input payload" &&
            it.logPath == "/log/path" &&
            it.geography == "US" &&
            it.mutexKey == "mutex-this" &&
            it.type == WorkloadType.SYNC &&
            it.deadline!!.equals(now.plusHours(2))
        },
      )
    }
  }

  @Test
  fun `test create workload id conflict`() {
    every { workloadRepository.existsById(WORKLOAD_ID) }.returns(true)
    assertThrows<ConflictException> {
      workloadHandler.createWorkload(WORKLOAD_ID, null, "", "", "US", "mutex-this", io.airbyte.config.WorkloadType.SYNC, UUID.randomUUID(), now)
    }
  }

  @Test
  fun `test create workload mutex conflict`() {
    every { workloadRepository.existsById(WORKLOAD_ID) }.returns(false)
    every {
      workloadRepository.searchByMutexKeyAndStatusInList(
        "mutex-this",
        WorkloadHandlerImpl.ACTIVE_STATUSES,
      )
    }.returns(listOf(Fixtures.workload()))
    assertThrows<ConflictException> {
      workloadHandler.createWorkload(WORKLOAD_ID, null, "", "", "US", "mutex-this", io.airbyte.config.WorkloadType.SYNC, UUID.randomUUID(), now)
    }
  }

  @Test
  fun `test get workloads`() {
    val domainWorkload =
      Fixtures.workload(
        id = WORKLOAD_ID,
        dataplaneId = null,
        status = WorkloadStatus.PENDING,
        workloadLabels = null,
        inputPayload = "a payload",
        logPath = "/log/path",
        geography = "US",
        mutexKey = "mutex-this",
        type = WorkloadType.DISCOVER,
      )
    every { workloadRepository.search(any(), any(), any()) }.returns(listOf(domainWorkload))
    val workloads = workloadHandler.getWorkloads(listOf("dataplane1"), listOf(ApiWorkloadStatus.CLAIMED, ApiWorkloadStatus.FAILURE), null)
    assertEquals(1, workloads.size)
    assertEquals(WORKLOAD_ID, workloads[0].id)
    assertEquals("a payload", workloads[0].inputPayload)
    assertEquals("/log/path", workloads[0].logPath)
    assertEquals("US", workloads[0].geography)
    assertEquals("mutex-this", workloads[0].mutexKey)
    assertEquals(io.airbyte.config.WorkloadType.DISCOVER, workloads[0].type)
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["CLAIMED", "LAUNCHED", "RUNNING"])
  fun `test successfulHeartbeat`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )
    every { workloadRepository.update(eq(WORKLOAD_ID), any(), ofType(OffsetDateTime::class), eq(now.plusMinutes(10))) }.returns(Unit)
    workloadHandler.heartbeat(WORKLOAD_ID, now.plusMinutes(10))
    verify { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.RUNNING), any(), eq(now.plusMinutes(10))) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["CANCELLED", "FAILURE", "SUCCESS", "PENDING"])
  fun `test nonAuthorizedHeartbeat`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )
    assertThrows<InvalidStatusTransitionException> { workloadHandler.heartbeat(WORKLOAD_ID, now) }
  }

  @Test
  fun `test workload not found when claiming workload`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.claimWorkload(WORKLOAD_ID, DATAPLANE_ID, now) }
  }

  @Test
  fun `test claiming workload has already been claimed by another plane`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          dataplaneId = "otherDataplaneId",
          status = WorkloadStatus.CLAIMED,
        ),
      ),
    )
    assertFalse(workloadHandler.claimWorkload(WORKLOAD_ID, DATAPLANE_ID, now))
  }

  @Test
  fun `test claiming pending workload has already been claimed by the same plane`() {
    every { workloadRepository.update(WORKLOAD_ID, DATAPLANE_ID, WorkloadStatus.CLAIMED, eq(now.plusMinutes(20))) }.returns(Unit)
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          dataplaneId = DATAPLANE_ID,
          status = WorkloadStatus.PENDING,
        ),
      ),
    )
    assertTrue(workloadHandler.claimWorkload(WORKLOAD_ID, DATAPLANE_ID, now.plusMinutes(20)))
  }

  @Test
  fun `test claiming claimed workload has already been claimed by the same plane`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          dataplaneId = DATAPLANE_ID,
          status = WorkloadStatus.CLAIMED,
        ),
      ),
    )
    assertTrue(workloadHandler.claimWorkload(WORKLOAD_ID, DATAPLANE_ID, now))
  }

  @Test
  fun `test claiming running workload has already been claimed by the same plane`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          dataplaneId = DATAPLANE_ID,
          status = WorkloadStatus.RUNNING,
        ),
      ),
    )
    assertThrows<InvalidStatusTransitionException> { workloadHandler.claimWorkload(WORKLOAD_ID, DATAPLANE_ID, now) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["RUNNING", "LAUNCHED", "SUCCESS", "FAILURE", "CANCELLED"])
  fun `test claiming workload that is not pending`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )
    assertThrows<InvalidStatusTransitionException> { workloadHandler.claimWorkload(WORKLOAD_ID, DATAPLANE_ID, now) }
  }

  @Test
  fun `test successful claim`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          dataplaneId = null,
        ),
      ),
    )

    every { workloadRepository.update(any(), any(), ofType(WorkloadStatus::class), eq(now.plusMinutes(20))) } just Runs

    assertTrue(workloadHandler.claimWorkload(WORKLOAD_ID, DATAPLANE_ID, now.plusMinutes(20)))

    verify { workloadRepository.update(WORKLOAD_ID, DATAPLANE_ID, WorkloadStatus.CLAIMED, eq(now.plusMinutes(20))) }
  }

  @Test
  fun `test workload not found when cancelling workload`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.cancelWorkload(WORKLOAD_ID, "test", "test cancel") }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["SUCCESS", "FAILURE"])
  fun `test cancel workload in terminal state`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    assertThrows<InvalidStatusTransitionException> { workloadHandler.cancelWorkload(WORKLOAD_ID, "test", "invalid cancel") }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["CLAIMED", "LAUNCHED", "RUNNING", "PENDING"])
  fun `test successful cancel`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    every { workloadRepository.update(any(), ofType(WorkloadStatus::class), eq("test"), eq("test cancel"), null) } just Runs

    workloadHandler.cancelWorkload(WORKLOAD_ID, "test", "test cancel")
    verify { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.CANCELLED), eq("test"), eq("test cancel"), null) }
  }

  @Test
  fun `test noop cancel`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = WorkloadStatus.CANCELLED,
        ),
      ),
    )

    workloadHandler.cancelWorkload(WORKLOAD_ID, "test", "test cancel again")
    verify(exactly = 0) { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.CANCELLED), "test", "test cancel again", null) }
  }

  @Test
  fun `test workload not found when failing workload`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.failWorkload(WORKLOAD_ID, "test", "fail") }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["SUCCESS", "CANCELLED"])
  fun `test fail workload in inactive status`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    assertThrows<InvalidStatusTransitionException> { workloadHandler.failWorkload(WORKLOAD_ID, "test", "fail") }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["CLAIMED", "LAUNCHED", "RUNNING"])
  fun `test failing workload succeeded`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    every { workloadRepository.update(any(), ofType(WorkloadStatus::class), eq("test"), eq("failing a workload"), null) } just Runs

    workloadHandler.failWorkload(WORKLOAD_ID, "test", "failing a workload")
    verify { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.FAILURE), eq("test"), eq("failing a workload"), null) }
  }

  @Test
  fun `test noop failure`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = WorkloadStatus.FAILURE,
        ),
      ),
    )

    workloadHandler.failWorkload(WORKLOAD_ID, "test", "noop")
    verify(exactly = 0) { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.FAILURE), eq("test"), eq("noop"), null) }
  }

  @Test
  fun `test workload not found when succeeding workload`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.succeedWorkload(WORKLOAD_ID) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["PENDING", "CANCELLED", "FAILURE"])
  fun `test succeed workload in inactive status`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    assertThrows<InvalidStatusTransitionException> { workloadHandler.succeedWorkload(WORKLOAD_ID) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["CLAIMED", "LAUNCHED", "RUNNING"])
  fun `test succeeding workload succeeded`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    every { workloadRepository.update(any(), ofType(WorkloadStatus::class), null) } just Runs

    workloadHandler.succeedWorkload(WORKLOAD_ID)
    verify { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.SUCCESS), null) }
  }

  @Test
  fun `test noop success`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = WorkloadStatus.SUCCESS,
        ),
      ),
    )

    workloadHandler.succeedWorkload(WORKLOAD_ID)
    verify(exactly = 0) { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.SUCCESS), null) }
  }

  @Test
  fun `test workload not found when setting status to running`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.setWorkloadStatusToRunning(WORKLOAD_ID, now) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["SUCCESS", "CANCELLED", "FAILURE"])
  fun `test set workload status to running when workload is in terminal state`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    assertThrows<InvalidStatusTransitionException> { workloadHandler.setWorkloadStatusToRunning(WORKLOAD_ID, now) }
  }

  @Test
  fun `test set workload status to running on unclaimed workload`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          dataplaneId = null,
          status = WorkloadStatus.PENDING,
        ),
      ),
    )

    assertThrows<InvalidStatusTransitionException> { workloadHandler.setWorkloadStatusToRunning(WORKLOAD_ID, now) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["CLAIMED", "LAUNCHED"])
  fun `test set workload status to running succeeded`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    every { workloadRepository.update(any(), ofType(WorkloadStatus::class), any()) } just Runs

    workloadHandler.setWorkloadStatusToRunning(WORKLOAD_ID, now.plusMinutes(10))
    verify { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.RUNNING), eq(now.plusMinutes(10))) }
  }

  @Test
  fun `test noop when setting workload status to running`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = WorkloadStatus.RUNNING,
        ),
      ),
    )

    workloadHandler.setWorkloadStatusToRunning(WORKLOAD_ID, now.plusMinutes(10))
    verify(exactly = 0) { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.RUNNING), eq(now.plusMinutes(10))) }
  }

  @Test
  fun `test workload not found when setting status to launched`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.setWorkloadStatusToLaunched(WORKLOAD_ID, now) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["PENDING", "SUCCESS", "CANCELLED", "FAILURE"])
  fun `test set workload status to launched when is not in claimed state`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    assertThrows<InvalidStatusTransitionException> { workloadHandler.setWorkloadStatusToLaunched(WORKLOAD_ID, now) }
  }

  @Test
  fun `test set workload status to launched succeeded`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = WorkloadStatus.CLAIMED,
        ),
      ),
    )

    every { workloadRepository.update(any(), ofType(WorkloadStatus::class), any()) } just Runs

    workloadHandler.setWorkloadStatusToLaunched(WORKLOAD_ID, now.plusMinutes(10))
    verify { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.LAUNCHED), eq(now.plusMinutes(10))) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["LAUNCHED", "RUNNING"])
  fun `test set workload status to launched is a noop`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = workloadStatus,
        ),
      ),
    )

    every { workloadRepository.update(any(), ofType(WorkloadStatus::class), any()) } just Runs

    workloadHandler.setWorkloadStatusToLaunched(WORKLOAD_ID, now.plusMinutes(10))
    verify(exactly = 0) { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.LAUNCHED), eq(now.plusMinutes(10))) }
  }

  @Test
  fun `test noop when setting workload status to launched`() {
    every { workloadRepository.findById(WORKLOAD_ID) }.returns(
      Optional.of(
        Fixtures.workload(
          id = WORKLOAD_ID,
          status = WorkloadStatus.LAUNCHED,
        ),
      ),
    )

    workloadHandler.setWorkloadStatusToLaunched(WORKLOAD_ID, now.plusMinutes(10))
    verify(exactly = 0) { workloadRepository.update(eq(WORKLOAD_ID), eq(WorkloadStatus.LAUNCHED), eq(now.plusMinutes(10))) }
  }

  @Test
  fun `test get workload running before`() {
    every { workloadRepository.searchByTypeStatusAndCreationDate(any(), eq(listOf(WorkloadStatus.RUNNING)), any(), any()) }
      .returns(listOf())
    val dataplaneIds = listOf("dataplaneId")
    val workloadTypes = listOf(ApiWorkloadType.CHECK)
    val createdAt = OffsetDateTime.now()

    workloadHandler.getWorkloadsRunningCreatedBefore(dataplaneIds, workloadTypes, createdAt)

    verify {
      workloadRepository.searchByTypeStatusAndCreationDate(
        dataplaneIds,
        listOf(WorkloadStatus.RUNNING),
        listOf(WorkloadType.CHECK),
        createdAt,
      )
    }
  }

  @Test
  fun `offsetDateTime method should always return current time`() {
    val workloadHandlerImpl = WorkloadHandlerImpl(mockk<WorkloadRepository>(), TestClient())
    val offsetDateTime = workloadHandlerImpl.offsetDateTime()
    Thread.sleep(10)
    val offsetDateTimeAfter10Ms = workloadHandlerImpl.offsetDateTime()
    assertTrue(offsetDateTimeAfter10Ms.isAfter(offsetDateTime))
  }

  object Fixtures {
    val workloadRepository = mockk<WorkloadRepository>()
    const val WORKLOAD_ID = "test"
    const val DATAPLANE_ID = "dataplaneId"
    val workloadHandler = spyk(WorkloadHandlerImpl(workloadRepository, TestClient(mapOf("platform.enforce-mutex-key-on-create" to true))))

    fun workload(
      id: String = WORKLOAD_ID,
      dataplaneId: String? = null,
      status: WorkloadStatus = WorkloadStatus.PENDING,
      workloadLabels: List<io.airbyte.workload.repository.domain.WorkloadLabel>? = listOf(),
      inputPayload: String = "",
      logPath: String = "/",
      geography: String = "US",
      mutexKey: String = "",
      type: WorkloadType = WorkloadType.SYNC,
    ): Workload =
      Workload(
        id = id,
        dataplaneId = dataplaneId,
        status = status,
        workloadLabels = workloadLabels,
        inputPayload = inputPayload,
        logPath = logPath,
        geography = geography,
        mutexKey = mutexKey,
        type = type,
      )
  }
}

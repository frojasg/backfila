package app.cash.backfila.service

import app.cash.backfila.BackfilaTestingModule
import app.cash.backfila.api.ConfigureServiceAction
import app.cash.backfila.client.Connectors.ENVOY
import app.cash.backfila.dashboard.CreateBackfillAction
import app.cash.backfila.dashboard.CreateBackfillRequest
import app.cash.backfila.dashboard.StartBackfillAction
import app.cash.backfila.dashboard.StartBackfillRequest
import app.cash.backfila.fakeCaller
import app.cash.backfila.protos.service.ConfigureServiceRequest
import com.google.inject.Module
import misk.scope.ActionScope
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import misk.time.FakeClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.inject.Inject

@MiskTest(startService = true)
class LeaseHunterTest {
  @Suppress("unused")
  @MiskTestModule
  val module: Module = BackfilaTestingModule()

  @Inject lateinit var configureServiceAction: ConfigureServiceAction
  @Inject lateinit var createBackfillAction: CreateBackfillAction
  @Inject lateinit var startBackfillAction: StartBackfillAction
  @Inject lateinit var scope: ActionScope
  @Inject lateinit var leaseHunter: LeaseHunter
  @Inject lateinit var clock: FakeClock

  @Test
  fun noBackfillsNoLease() {
    assertThat(leaseHunter.hunt()).isEmpty()
  }

  @Test
  fun pausedBackfillNotLeased() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(ConfigureServiceRequest.Builder()
          .backfills(listOf(
              ConfigureServiceRequest.BackfillData("ChickenSandwich", "Description", listOf(), null,
                  null, false)))
          .connector_type(ENVOY)
          .build())
    }
    scope.fakeCaller(user = "molly") {
      createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))
    }
    assertThat(leaseHunter.hunt()).isEmpty()
  }

  @Test
  fun runningBackfillLeased() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(ConfigureServiceRequest.Builder()
          .backfills(listOf(
              ConfigureServiceRequest.BackfillData("ChickenSandwich", "Description", listOf(), null,
                  null, false)))
          .connector_type(ENVOY)
          .build())
    }
    scope.fakeCaller(user = "molly") {
      val response = createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))

      val id = response.id
      startBackfillAction.start(id, StartBackfillRequest())
    }

    val runners = leaseHunter.hunt()
    assertThat(runners).hasSize(1)
    val runner = runners.single()
    assertThat(runner.backfillName).isEqualTo("ChickenSandwich")

    val runners2 = leaseHunter.hunt()
    assertThat(runners2).hasSize(1)
    val runner2 = runners2.single()

    assertThat(runner.instanceId).isNotEqualTo(runner2.instanceId)
  }

  @Test
  fun activeLeaseNotStolen() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(ConfigureServiceRequest.Builder()
          .backfills(listOf(
              ConfigureServiceRequest.BackfillData("ChickenSandwich", "Description", listOf(), null,
                  null, false)))
          .connector_type(ENVOY)
          .build())
    }
    scope.fakeCaller(user = "molly") {
      val response = createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))

      val id = response.id
      startBackfillAction.start(id, StartBackfillRequest())
    }

    val runners = leaseHunter.hunt()
    assertThat(runners).hasSize(1)

    val runners2 = leaseHunter.hunt()
    assertThat(runners2).hasSize(1)

    assertThat(runners.single().instanceId).isNotEqualTo(runners2.single().instanceId)
    assertThat(leaseHunter.hunt()).isEmpty()

    // Advance past the lease expiry.
    clock.add(LeaseHunter.LEASE_DURATION.plusSeconds(1))
    assertThat(leaseHunter.hunt()).hasSize(1)
  }
}
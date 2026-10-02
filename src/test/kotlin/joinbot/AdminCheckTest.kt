package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

private class CountingTg(private val d: FakeTg) : Tg by d {
    var adminsCalls = 0
    override suspend fun admins(chatId: Long): List<Admin>? { adminsCalls++; return d.admins(chatId) }
}

class AdminCheckTest : StringSpec({
    "cache serves within ttl, fresh bypasses" {
        val fake = FakeTg().apply { adminsOf[-100] = listOf(Admin(1, "a", false, true)) }
        val tg = CountingTg(fake); val clock = TestClock()
        val check = AdminCheck(tg, clock)
        check.canDecide(-100, 1) shouldBe true
        check.canDecide(-100, 1) shouldBe true
        tg.adminsCalls shouldBe 1
        check.canDecide(-100, 1, fresh = true) shouldBe true
        tg.adminsCalls shouldBe 2
        clock.now = clock.now.plus(Duration.ofSeconds(61))
        check.canDecide(-100, 1) shouldBe true
        tg.adminsCalls shouldBe 3
    }

    "bots and admins without invite right are not deciders" {
        val fake = FakeTg().apply {
            adminsOf[-100] = listOf(Admin(1, "a", false, true), Admin(2, "b", true, true), Admin(3, "c", false, false))
        }
        val check = AdminCheck(fake, TestClock())
        check.deciders(-100) shouldBe listOf(1L)
        check.canDecide(-100, 2) shouldBe false
        check.canDecide(-100, 3) shouldBe false
    }

    "admins() failure means nobody can decide" {
        val check = AdminCheck(FakeTg(), TestClock())
        check.canDecide(-100, 1) shouldBe false
        check.deciders(-100) shouldBe emptyList()
    }

    "a failed admins() lookup is not cached" {
        val fake = FakeTg()
        val check = AdminCheck(fake, TestClock())
        check.canDecide(-100, 1) shouldBe false
        fake.adminsOf[-100] = listOf(Admin(1, "a", false, true))
        check.canDecide(-100, 1) shouldBe true
    }
})

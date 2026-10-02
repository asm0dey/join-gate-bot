package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class AdminCheckTest : StringSpec({
    "cache serves within ttl, fresh bypasses" {
        val fake = FakeTelegram().apply { adminsOf[-100] = listOf(Admin(1, "a", false, true)) }
        val clock = TestClock()
        val check = AdminCheck(fake.bot, clock)
        check.canDecide(-100, 1) shouldBe true
        check.canDecide(-100, 1) shouldBe true
        fake.adminsCalls.size shouldBe 1
        check.canDecide(-100, 1, fresh = true) shouldBe true
        fake.adminsCalls.size shouldBe 2
        clock.now = clock.now.plus(Duration.ofSeconds(61))
        check.canDecide(-100, 1) shouldBe true
        fake.adminsCalls.size shouldBe 3
    }

    "bots and admins without invite right are not deciders" {
        val fake = FakeTelegram().apply {
            adminsOf[-100] = listOf(Admin(1, "a", false, true), Admin(2, "b", true, true), Admin(3, "c", false, false))
        }
        val check = AdminCheck(fake.bot, TestClock())
        check.deciders(-100) shouldBe listOf(1L)
        check.canDecide(-100, 2) shouldBe false
        check.canDecide(-100, 3) shouldBe false
    }

    "admins() failure means nobody can decide" {
        val check = AdminCheck(FakeTelegram().bot, TestClock())
        check.canDecide(-100, 1) shouldBe false
        check.deciders(-100) shouldBe emptyList()
    }

    "a failed admins() lookup is not cached" {
        val fake = FakeTelegram()
        val check = AdminCheck(fake.bot, TestClock())
        check.canDecide(-100, 1) shouldBe false
        fake.adminsOf[-100] = listOf(Admin(1, "a", false, true))
        check.canDecide(-100, 1) shouldBe true
    }
})

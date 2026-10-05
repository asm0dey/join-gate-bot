package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RosterTest : StringSpec({
    "seen writes once" {
        val db = testDb("roster-once")
        GroupRepo(db).upsert(-1, "Club", true)
        val members = MemberRepo(db)
        val r = Roster(members)
        r.seen(-1, 5); r.seen(-1, 5)
        members.count(-1) shouldBe 1
        members.remove(-1, 5)
        r.seen(-1, 5)
        members.known(-1, 5) shouldBe false // cached
    }

    "left forgets the member and the cache" {
        val db = testDb("roster-left")
        GroupRepo(db).upsert(-1, "Club", true)
        val members = MemberRepo(db)
        val r = Roster(members)
        r.seen(-1, 5)
        r.left(-1, 5)
        members.known(-1, 5) shouldBe false
        r.seen(-1, 5)
        members.known(-1, 5) shouldBe true
    }
})

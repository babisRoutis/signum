import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldNotBe

class Test : FreeSpec({

    "This dummy test" {
        "is just making sure" shouldNotBe "that iOS tests are indeed running"
    }
})
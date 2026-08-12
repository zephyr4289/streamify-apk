fun test(a: Int = 1, b: Int = 2, c: Int, d: () -> Unit) {}
fun main() {
    test(c = 3, d = {})
}

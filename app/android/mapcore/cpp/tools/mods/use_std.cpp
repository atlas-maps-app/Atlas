// `import std` alone: the C++23 standard library as a named module.
import std;

int main() {
    std::vector<int> v{1, 2, 3};
    std::println("import std: {} values, flat_map {}", v.size(), std::flat_map<int, int>{{1, 2}}.size());
    return 0;
}

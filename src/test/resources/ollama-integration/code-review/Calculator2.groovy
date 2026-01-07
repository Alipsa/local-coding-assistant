package se.alipsa.lca.test

class Calculator2 {

  int subtract(int a, int b) {
    return a - b
  }

  double calculatePercentage(double value, double total) {
    if (total == 0) {
      throw new ArithmeticException("Cannot divide by zero")
    }
    return (value / total) * 100
  }
}

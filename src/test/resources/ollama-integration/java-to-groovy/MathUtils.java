package se.alipsa.lca.test;

public class MathUtils {

  public static double calculateAverage(double[] numbers) {
    if (numbers == null || numbers.length == 0) {
      return 0.0;
    }

    double sum = 0.0;
    for (double num : numbers) {
      sum += num;
    }

    double average = sum / numbers.length;
    return Math.round(average * 100.0) / 100.0;
  }

  public static int findMaximum(int a, int b, int c) {
    int max = Math.max(a, b);
    max = Math.max(max, c);
    return max;
  }

  public static double applyDiscount(double price, double discountPercent) {
    double discountAmount = price * (discountPercent / 100.0);
    double finalPrice = price - discountAmount;
    return Math.round(finalPrice * 100.0) / 100.0;
  }
}

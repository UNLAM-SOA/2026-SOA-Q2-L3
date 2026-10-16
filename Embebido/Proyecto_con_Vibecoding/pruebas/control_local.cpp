#include <stdio.h>
#include "../PruebasControl.h"

int main()
{
  const int falla = probarControlLocal();
  if (falla != 0) {
    fprintf(stderr, "Falla en PruebasControl.h:%d\n", falla);
    return 1;
  }
  puts("Control local: OK");
  return 0;
}

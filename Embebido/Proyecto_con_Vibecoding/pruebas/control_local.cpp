#include <stdio.h>
#include "../PruebasControl.h"
#include "../PruebasMqtt.h"

int main()
{
  const int falla = probarControlLocal();
  if (falla != 0) {
    fprintf(stderr, "Falla en PruebasControl.h:%d\n", falla);
    return 1;
  }
  puts("Control local: OK");
  const int fallaMqtt = probarMqtt();
  if (fallaMqtt != 0) {
    fprintf(stderr, "Falla en PruebasMqtt.h:%d\n", fallaMqtt);
    return 1;
  }
  puts("Protocolo MQTT: OK");
  return 0;
}

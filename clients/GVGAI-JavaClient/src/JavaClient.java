/**
 * Created by Daniel on 04.03.2017.
 */

/**
 * Class to generate and run the client's side of the communication protocol.
 */
public class JavaClient
{
    public static void main(String[] args)
    {
        ClientComm ccomm = new ClientComm();
        ccomm.start();
    }
}

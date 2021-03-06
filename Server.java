import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.*;
import javax.xml.bind.DatatypeConverter;

public class Server{
    private int portNum;
    private Map<String,SocketKey> allUsers;
    private cryptotest crypt = new cryptotest();
    private PublicKey pubKey;
    private PrivateKey privKey;

    public Server(int port){
        portNum = port;
        allUsers =  new ConcurrentHashMap<String,SocketKey>();

        //Generate public and private keys.
        crypt.setPublicKey("RSApub.der");
        crypt.setPrivateKey("RSApriv.der");
        pubKey = crypt.getPublicKey();
        privKey = crypt.getPrivateKey();

        runServer();
    }

    public void runServer(){
		//TODO: ask about closing threads on exit.
        try{
            ServerSocketChannel channel = ServerSocketChannel.open();
            channel.bind(new InetSocketAddress(portNum));
            System.out.println("Server conected...");

            while (true){

                SocketChannel sc = channel.accept();

                Thread t = new Thread(new Runnable() {
                    public void run() {
                        //Code to run in thread
                        runThread(sc);
                    }
                });
                t.start();
            }
        }
        catch(IOException e){
            System.out.println("Exception in the server class.");
            System.out.println(e);
        }
    }



    public void runThread(SocketChannel s){
        SocketChannel sc = s;
        String userName = "";
        try{
            //Send over the public key to the client.
            byte[] keyBytes = pubKey.getEncoded();
            ByteBuffer keyBuff = ByteBuffer.wrap(keyBytes);
            sc.write(keyBuff);

            //Get the secret key from the client. (Also decrypt it)
            ByteBuffer secKeyBuff = ByteBuffer.allocate(256);
            sc.read(secKeyBuff);
            byte[] dcrypKey = crypt.RSADecrypt(secKeyBuff.array());
            SecretKey secKey = new SecretKeySpec(dcrypKey,"AES");

            //Take in the IV bytes that which we need for encrypting with the secret key.
            ByteBuffer IVBytesBuff = ByteBuffer.allocate(16);
            sc.read(IVBytesBuff);
            IvParameterSpec iv = new IvParameterSpec(IVBytesBuff.array());

            //Get the clients username and decrypt.
            ByteBuffer userBuf = ByteBuffer.allocate(1024);
            sc.read(userBuf);
            userName = new String(crypt.decrypt(userBuf.array(), secKey, iv));
            userName = userName.trim();
            System.out.println(userName);

            if (!checkName(sc, userName)){
                return;
            }
			if(userName.equals("dustin") || userName.equals("walt")){
				userBuf = ByteBuffer.wrap("/admin".getBytes());
				sc.write(ByteBuffer.wrap(crypt.encrypt(formatArray(userBuf.array()), secKey, iv)));
			}
            //userName = removeSlash(userName);
            System.out.println("User added: " + userName);

            //**** USE THIS WHEN THE MAP IS CHANED *******
            allUsers.put(userName, new SocketKey(sc, secKey, iv));

            boolean connected = true;
            //Send message to all users on the server.
            while (connected){
                ByteBuffer buff = ByteBuffer.allocate(1024);
                sc.read(buff);
                String message = new String(crypt.decrypt(buff.array(), secKey, iv));
                message.trim();

                //check if message is a command...
                if (!isCommand(userName, sc, message)){
                    //Else send it to all...
                    message = userName + ": " + message.trim();
                    System.out.println(message);
                    buff = ByteBuffer.wrap(message.getBytes());
                    sendMessageAll(buff);
                }
            }

            sc.close();
        }
        catch(IOException e){
            allUsers.remove(userName);
            System.out.println(userName + " has disconnected.");
        }
    }

    public boolean checkName(SocketChannel s, String n){
        for(String i: allUsers.keySet()){
            if (n.equals(i)){
                ByteBuffer b = ByteBuffer.wrap("Username in use!".getBytes());

				try{
					s.write(b);
				}catch(Exception e){
					System.out.println("Error in checkName: " + e);
				}
                exit(s);
                return false;
            }
        }
        return true;
    }


    public String removeSlash(String s){
        boolean remove = false;
        StringBuilder sb = new StringBuilder(s);
        for (int i = s.length()-1; i >= 0; i--){
            if (s.charAt(i) == (' '))
                remove = true;
            if (remove)
                sb.deleteCharAt(i);
        }
        return sb.toString();
    }

    public boolean isCommand(String senderName, SocketChannel sender, String m){
        String[] contents = m.split(" ");
        contents[0] = contents[0].trim();

        switch(contents[0]){
            case "/exit":
                exit(sender);
                return true;
            case "/pm":
                //Get the message of the user and turn it back to a string.
                String temp = "";
                for (int i = 2; i < contents.length; i++){
                    temp += contents[i] + " ";
                }
                temp = temp.trim();
                //Send the message to a specific user.
                sendPM(senderName, sender, contents[1], temp);
                return true;
            case "/kick":
                System.out.println("User to kick: " + contents[1]);
                kick(allUsers.get(senderName), contents[1]);
                return true;
			case "/list":
				System.out.println(senderName + " requesting user list.");
				sendList(allUsers.get(senderName));
				return true;
        }

        return false;
    }

    public void sendPM(String sender, SocketChannel sChan, String user, String m){
        for(String i: allUsers.keySet()){
			if(i.equals(user)){
				String message = "PM from " + sender + ": " + m;
				try{
					SocketKey s = allUsers.get(i);
					ByteBuffer buff = ByteBuffer.wrap(crypt.encrypt(formatArray(message.getBytes()), s.getSecret(), s.getIv()));
					s.getChannel().write(buff);
					message = "PM to " + user + ": " + m;
					SocketKey s2 = allUsers.get(sender);
					buff = ByteBuffer.wrap(crypt.encrypt(formatArray(message.getBytes()), s2.getSecret(), s2.getIv()));
					s2.getChannel().write(buff);
				}catch(Exception e){
					System.out.println("Error sending PM: " + e);
				}
			}
		}
		return;
    }

	public void kick(SocketKey s, String user){
		//sendPM(user, "You have been kicked by an admin. Deuces.");
        user = user.trim();
		for(String i: allUsers.keySet()){
			if(i.equals(user)){
				SocketChannel kickee = allUsers.get(i).getChannel();
				SocketKey kickeeKey = allUsers.get(i);
				ByteBuffer b = ByteBuffer.wrap("You have been kicked by an admin.".getBytes());
				try{
					kickee.write(ByteBuffer.wrap(crypt.encrypt(formatArray(b.array()), kickeeKey.getSecret(), kickeeKey.getIv())));
					kickee.close();
                    System.out.println("Closed: " + user);
				}catch(Exception e){
					System.out.println("Error kicking user: " + e);
				}
				allUsers.remove(i);
                break;
			}
		}
		return;
	}

	public void exit(SocketChannel user){
        System.out.println("exiting...");
		try{
			user.close();
		}catch(Exception e){
			System.out.println("Error on exit: " + e);
		}
		return;
	}

    public void sendMessageAll(ByteBuffer b){
        for (SocketKey s : allUsers.values()){
            try{
                b.position(0);
                s.getChannel().write(ByteBuffer.wrap(crypt.encrypt(formatArray(b.array()), s.getSecret(), s.getIv())));
            }
            catch(Exception e){
                System.out.println("Error in send all.");
                System.out.println(e);
            }
        }

    }

    public void sendList(SocketKey s){
        String names = "******** Online ********\n";
        for (String n : allUsers.keySet()){
            names += n + "\n";
        }
		names += "******** Online ********";
        try{
            ByteBuffer b = ByteBuffer.wrap(crypt.encrypt(formatArray(names.getBytes()), s.getSecret(), s.getIv()));

    		s.getChannel().write(b);
        }
        catch(Exception e){
            System.out.println("Error sending list " + e);
        }
    }


    public static void main(String[] args){
        int portNum = 0;
        boolean correct = false;
        boolean first = true;
        while(!correct){
            if (args.length != 1 || !first){
                Console cons = System.console();
                String portString = cons.readLine("PortNum: ");
                portNum = Integer.parseInt(portString);

            }
            else{
                portNum = Integer.parseInt(args[0]);
                first = false;
            }

            if (portNum > 1024 && portNum < 65535)
                correct = true;
            else
                System.out.println("Invalid port. Try again.");
        }

        Server s = new Server(portNum);
    }

	public byte[] formatArray(byte[] arr){
        byte[] temp = new byte[1024];
        for (int i = 0; i < temp.length; i++){
            if (i < arr.length)
                temp[i] = arr[i];
        }
        return temp;
    }

}

class SocketKey{
    private SocketChannel chan;
    private SecretKey key;
    private IvParameterSpec iv;

    public SocketKey(SocketChannel sc, SecretKey sk, IvParameterSpec ivps){
        chan = sc;
        key = sk;
		iv = ivps;
    }

    public SocketChannel getChannel(){
        return chan;
    }

    public SecretKey getSecret(){
        return key;
    }

	public IvParameterSpec getIv(){
		return iv;
	}
}

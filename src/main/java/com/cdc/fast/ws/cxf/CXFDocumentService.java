package com.cdc.fast.ws.cxf;

import com.cdc.fast.ws.sei.DataFileVO;
import com.cdc.fast.ws.sei.DocumentContentVO;
import com.cdc.fast.ws.sei.DocumentService;
import com.cdc.fast.ws.sei.MetaFileVO;
import com.cdc.pcp.common.manager.Extension;
import com.cdc.pcp.common.model.*;
import com.cdc.pcp.common.service.PCPExtensionService;
import com.cdc.pcp.common.service.PCPSubscriberService;
import com.cdc.pcp.common.service.PCPWorkflowService;
import com.cdc.pcp.common.service.exception.DeleteNodesException;
import com.cdc.pcp.common.service.exception.UploadFileNodesException;
import com.healthmarketscience.rmiio.RemoteInputStream;
import com.healthmarketscience.rmiio.RemoteInputStreamClient;
import org.springframework.beans.factory.annotation.Autowired;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.jws.WebService;
import javax.mail.util.ByteArrayDataSource;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlMimeType;
import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFactory;
import javax.xml.soap.SOAPFault;
import javax.xml.ws.soap.SOAPFaultException;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/* wsdlLocation = "https://localhost/parapheur-soap/soap/v1/Documents?wsdl"
* wsdlLocation = "documents.wsdl"
*
*/
@WebService(endpointInterface = "com.cdc.fast.ws.sei.DocumentService", portName = "DocumentPort", targetNamespace = "documentTarget")
public class CXFDocumentService extends AbstractCommonService implements DocumentService {

    private static final Logger logger = Logger.getLogger(CXFDocumentService.class.getName());
    @Autowired
    private PCPSubscriberService subscriberService;
    @Autowired
    private PCPExtensionService extensionService;
    @Autowired
    private PCPWorkflowService workflowService;

    @Override
    public DocumentContentVO download(String documentId) {

        if (hasPermissionToAccessNode(documentId)) {

            //
            ParapheurNodeInformation nodeInformation = nodeService.getParapheurNodeInformation(documentId);
            Extension extension = extensionService.getExtensionForFilename(nodeInformation.getFilename());

            //
            InputStream fileInputStream = null;
            try {
                RemoteInputStream remoteInputStream = nodeService.getNodeInputStream(documentId);
                fileInputStream = RemoteInputStreamClient.wrap(remoteInputStream);
            } catch (IOException e) {
                try {
                    SOAPFault fault = SOAPFactory.newInstance().createFault();
                    fault.setFaultString(e.getMessage());
                    throw new SOAPFaultException(fault);
                } catch (SOAPException e1) {
                    throw new RuntimeException("Problem downloading document : " + e1.getMessage());
                }
            }

            // prepare result
            DocumentContentVO contentVO = new DocumentContentVO();
            contentVO.setDocumentId(documentId);

            DataSource ds = null;
            try {
                ds = new ByteArrayDataSource(fileInputStream, extension.contentType);
                contentVO.setContent(new DataHandler(ds));
            } catch (Exception e) {
                try {
                    SOAPFault fault = SOAPFactory.newInstance().createFault();
                    fault.setFaultString(e.getMessage());
                    fault.setFaultCode(new QName("FILE ACCESS ERROR", "PARAPHEUR MESSAGE : Server"));
                    throw new SOAPFaultException(fault);
                } catch (SOAPException e1) {
                    throw new RuntimeException("Problem downloading document : " + e1.getMessage());
                }
            }

            //
            return contentVO;

        } else {
            try {
                SOAPFault fault = null;
                fault = SOAPFactory.newInstance().createFault();
                fault.setFaultString("ACCESS DENIED");
                fault.setFaultCode(new QName("ACCESS_DENIED", "PARAPHEUR MESSAGE : Access Denied"));
                throw new SOAPFaultException(fault);
            } catch (SOAPException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public String upload(String label, String comment, String subscriberId, String circuitId, DataFileVO dataFileVO) {

        // create temp file
        File tempFile = null;
        try {

            //
            InputStream inputStream = dataFileVO.getDataHandler().getInputStream();
            tempFile = buildTempFile(inputStream);

        } catch (IOException e) {
            SOAPFault fault = null;
            try {
                fault = SOAPFactory.newInstance().createFault();
                fault.setFaultString("Server Side Error : impossible to create a temporary file.\r\n" + e.getMessage());
                throw new SOAPFaultException(fault);
            } catch (SOAPException e1) {
                throw new RuntimeException(e1);
            }
        }

        // upload into the repository
        List<String> createdNodeRefs = null;
        String noderefId = null;
        try {
            UserInformation userInformation = userService.getUserInformation(getUsername());
            Set<Abonne> subscribers = subscriberService.getAbonneByUsername(getUsername());
            for (Abonne subscriber : subscribers) {
                if (subscriber.getSiren().equals(subscriberId)) {
                    userInformation.setCurrentAbonne(subscriber);
                    break;
                }
            }

            //
            if (userInformation.getCurrentAbonne() == null) {
                SOAPFault fault = null;
                try {
                    fault = SOAPFactory.newInstance().createFault();
                    fault.setFaultString("No current subscriber defined for current user or the subscriberId unknown.");
                    throw new SOAPFaultException(fault);
                } catch (SOAPException e1) {
                    throw new RuntimeException(e1);
                }
            }

            createdNodeRefs = nodeService.uploadFileNodes(Arrays.asList(new File[]{tempFile}), buildListOfOneElement(dataFileVO.getFilename()), buildListOfOneElement(label), buildListOfOneElement(comment), userInformation, circuitId);
            noderefId = ((createdNodeRefs.size() > 0) ? createdNodeRefs.get(0) : "");
            logger.info("Node created : " + noderefId);
        } catch (UploadFileNodesException e) {
            SOAPFault fault = null;
            try {
                fault = SOAPFactory.newInstance().createFault();
                fault.setFaultString(e.getMessage());
                throw new SOAPFaultException(fault);
            } catch (SOAPException e1) {
                throw new RuntimeException(e1);
            }
        }

        // return the nodeRefId of the created document
        return noderefId;
    }

    private File buildTempFile(InputStream inputStream) throws IOException {

        //

        File tempFile;
        tempFile = File.createTempFile("parapheur-", ".bin");
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(tempFile));
        //FileOutputStream outputStream = new FileOutputStream(tempFile);

        //
        byte[] buffer = new byte[8192];
        int bytesRead = 0;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            bufferedOutputStream.write(buffer);
        }

        //
        // bufferedOutputStream.flush();
        bufferedOutputStream.close();
        inputStream.close();
        return tempFile;
    }

    @Override
    public void delete(String documentId) {
        try {
            String result = nodeService.deleteNodesList(Arrays.asList(new String[]{documentId}));
        } catch (DeleteNodesException e) {
            SOAPFault fault = null;
            try {
                fault = SOAPFactory.newInstance().createFault();
                fault.setFaultString(e.getMessage());
                fault.setFaultCode("PCP_REMOVE_ERROR");
                throw new SOAPFaultException(fault);
            } catch (SOAPException e1) {
                throw new RuntimeException(e1);
            }
        }
    }

    @Override
    public String sendMeta(MetaFileVO meta) {

        // build meta data list from uploaded XML Stream


        // prepare data
        MetaDataList metaDataList = new MetaDataList();
        for (MetaData metaData : meta.getMetaDataList()) {
            metaDataList.addMetaData(metaData);
        }

        // make service call
        try {
            return workflowService.defineMetaData(metaDataList, meta.getDocumentId(), userService.getUserInformation(getUsername()));
        } catch (Exception e) {
            SOAPFault fault = null;
            try {
                fault = SOAPFactory.newInstance().createFault();
                fault.setFaultString(e.getMessage());
                fault.setFaultCode("PCP_REMOVE_ERROR");
                throw new SOAPFaultException(fault);
            } catch (SOAPException e1) {
                throw new RuntimeException(e1);
            }
        }
    }

    @Override
    public String uploadMeta(String documentId, @XmlMimeType("application/xml") DataHandler dataHandler) {


        try {

            // load XML Stream
            Object content = dataHandler.getContent();
            InputStream inputStream = (InputStream) content;
            String contentType = dataHandler.getContentType();

            /*
            BOMInputStream bomInputStream = new BOMInputStream(inputStream);
            if (bomInputStream.hasBOM(ByteOrderMark.UTF_8)) {
                bomInputStream.skip(ByteOrderMark.UTF_8.length());
            }
            */

            // dump stream
            byte[] buffer = new byte[1024];
            while (inputStream.read(buffer) != -1) {
                for (int i = 0; i < buffer.length; i++) {
                    System.out.println((char) buffer[i] + "-" + buffer[i]);
                }
            }


            // build Object
            JAXBContext jaxbContext = JAXBContext.newInstance(MetaDataList.class);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            MetaDataList metaDataList = (MetaDataList) unmarshaller.unmarshal(inputStream);

            // make remote service call
            return workflowService.defineMetaData(metaDataList, documentId, userService.getUserInformation(getUsername()));

        } catch (Exception e) {
            try {
                SOAPFault fault = null;
                fault = SOAPFactory.newInstance().createFault();
                fault.setFaultString(e.getMessage());
                fault.setFaultCode("PCP_UPLOAD_META_ERROR");
                throw new SOAPFaultException(fault);
            } catch (SOAPException e1) {
                throw new RuntimeException(e1);
            }
        }
    }

    private List<String> buildListOfOneElement(String item) {
        return Arrays.asList(new String[]{item});
    }
}
